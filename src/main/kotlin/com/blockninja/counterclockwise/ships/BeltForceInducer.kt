package com.blockninja.counterclockwise.ships

import com.simibubi.create.AllBlocks
import com.simibubi.create.content.kinetics.belt.BeltBlock
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity
import com.simibubi.create.content.kinetics.belt.BeltHelper
import com.simibubi.create.content.kinetics.belt.BeltSlope
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import org.joml.Matrix3d
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.primitives.AABBd
import org.joml.primitives.AABBdc
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.getShipsIntersecting
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

object BeltForceInducer {

    private const val CONTACT_EPS = 0.3
    private const val LATERAL_EPS = 0.0

    // Fraction of the contact slip cancelled per physics tick. 1.0 = perfect friction.
    private const val FRICTION_ALPHA = 1.0

    // Safety clamp so a broken transform can never produce an infinite force
    private const val MAX_SLIP = 100.0
    private const val STALE_TICKS = 10
    private const val WORLD_ID = -1L

    private data class BeltKey(val dimension: String, val shipId: Long, val pos: Long)

    private data class ContactRegion(val aabb: AABB, val sign: Int, val normalLocal: Vector3dc?)

    private data class BeltContact(
        val shipId: Long,
        val localPos: Vector3dc,
        val sign: Int,
        val normalLocal: Vector3dc?,
        val area: Double
    )

    private class BeltSegmentData(
        val surfaceVelLocal: Vector3dc,
        val controllerLocal: Long,
        val contacts: List<BeltContact>,
        val lastSeen: Int
    )

    private class PendingContact(
        val worldPos: Vector3d,
        val slip: Vector3d,
        val normalWorld: Vector3dc?,
        val holderPhys: PhysShip?
    )

    // Identifies one whole conveyor: holder ship + controller position
    private data class BeltGroupId(val holderId: Long, val controllerLocal: Long)

    private class BeltGroup {
        val contacts = mutableListOf<PendingContact>()
        var area = 0.0
    }

    private val belts = ConcurrentHashMap<BeltKey, BeltSegmentData>()
    private val tickCounter = AtomicInteger()

    @JvmStatic
    fun updateSegment(belt: BeltBlockEntity) {
        val level = belt.level as? ServerLevel ?: return
        val pos = belt.blockPos
        val state = belt.blockState

        if (!AllBlocks.BELT.has(level.getBlockState(pos)) || belt.getSpeed() == 0f) {
            removeSegment(level, pos)
            return
        }

        val holderShip = level.getLoadedShipManagingPos(pos)
        val key = BeltKey(level.dimensionId, holderShip?.id ?: WORLD_ID, pos.asLong())

        val beltVec = BeltHelper.getBeltVector(state)
        val speed = belt.getDirectionAwareBeltMovementSpeed().toDouble()
        val surfaceVelLocal = Vector3d(beltVec.x, beltVec.y, beltVec.z).mul(speed * 20.0)

        val contacts = findContacts(level, pos, state, holderShip)
        belts[key] = BeltSegmentData(surfaceVelLocal, belt.getController().asLong(), contacts, tickCounter.get())
    }

    @JvmStatic
    fun removeSegment(level: Level, pos: BlockPos) {
        val holderShip = level.getLoadedShipManagingPos(pos)
        belts.remove(BeltKey(level.dimensionId, holderShip?.id ?: WORLD_ID, pos.asLong()))
    }

    fun onGameTickEnd() {
        val tick = tickCounter.incrementAndGet()
        belts.entries.removeIf { (_, data) -> tick - data.lastSeen > STALE_TICKS }
    }

    @OptIn(PhysTickOnly::class)
    fun onPhysTick(physLevel: PhysLevel, delta: Double) {
        if (delta <= 0.0) return
        val dimension = physLevel.dimension

        // Gather every contact that wants to act on each ship this tick, grouped by
        // the conveyor it comes from, so that when several belts touch one ship
        // only the belt covering the most area on it takes effect
        val groups = HashMap<Long, HashMap<BeltGroupId, BeltGroup>>()

        belts.forEach { (key, data) ->
            if (key.dimension != dimension) return@forEach

            val holderPhys = if (key.shipId == WORLD_ID) null else physLevel.getShipById(key.shipId)
            if (key.shipId != WORLD_ID && holderPhys == null) return@forEach

            val shipToWorld = holderPhys?.transform?.shipToWorld
            val surfaceVelWorld = if (shipToWorld != null) {
                shipToWorld.transformDirection(Vector3d(data.surfaceVelLocal))
            } else {
                Vector3d(data.surfaceVelLocal)
            }

            val groupId = BeltGroupId(key.shipId, data.controllerLocal)

            for (contact in data.contacts) {
                val surfacePos = if (shipToWorld != null) {
                    shipToWorld.transformPosition(Vector3d(contact.localPos))
                } else {
                    Vector3d(contact.localPos)
                }

                val normalWorld = if (contact.normalLocal != null) {
                    if (shipToWorld != null) {
                        shipToWorld.transformDirection(Vector3d(contact.normalLocal))
                    } else {
                        Vector3d(contact.normalLocal)
                    }.normalize()
                } else {
                    null
                }

                val target = (if (contact.shipId == WORLD_ID) holderPhys
                else physLevel.getShipById(contact.shipId)) ?: continue

                // Friction acting at the base of a ship tips it over as it gets
                // pushed. Instead apply the force at the contact projected onto
                // the target's center-of-mass plane along the contact normal: the
                // contact height then carries no torque, and only horizontally
                // offset contacts yaw the ship, which is what a conveyor should do
                val worldPos = if (normalWorld != null) {
                    val comWorld = target.transform.shipToWorld
                        .transformPosition(Vector3d(target.centerOfMass))
                    val offset = Vector3d(surfacePos).sub(comWorld)
                    Vector3d(surfacePos).sub(Vector3d(normalWorld).mul(offset.dot(normalWorld)))
                } else {
                    surfacePos
                }

                val holderVel = if (holderPhys != null) pointVelocity(holderPhys, worldPos) else Vector3d()
                val beltVel = holderVel.add(Vector3d(surfaceVelWorld).mul(contact.sign.toDouble()))

                val group = groups.getOrPut(target.id) { HashMap() }.getOrPut(groupId) { BeltGroup() }
                group.area += contact.area

                if (contact.shipId == WORLD_ID) {
                    // Belt on a ship touching the static world: acts as a tread.
                    // The world contact point never moves, so stick means the belt
                    // surface velocity there should go to zero.
                    group.contacts.add(
                        PendingContact(worldPos, clampSlip(beltVel.negate(Vector3d())), normalWorld, null)
                    )
                } else {
                    val slip = clampSlip(beltVel.sub(pointVelocity(target, worldPos)))
                    group.contacts.add(PendingContact(worldPos, slip, normalWorld, holderPhys))
                }
            }
        }

        groups.forEach { (shipId, beltGroups) ->
            val body = physLevel.getShipById(shipId) ?: return@forEach
            val contacts = beltGroups.values.maxByOrNull { it.area }?.contacts ?: return@forEach

            val scale = FRICTION_ALPHA / (contacts.size * delta)
            for (contact in contacts) {
                val slip = tangentialSlip(contact.slip, contact.normalWorld)
                if (slip.lengthSquared() < 1e-12) continue

                val force = solveStickingForce(body, contact.worldPos, slip, scale)
                body.applyWorldForce(force, contact.worldPos)
                contact.holderPhys?.applyWorldForce(force.negate(Vector3d()), contact.worldPos)
            }
        }
    }

    // The belt surface velocity relative to whatever is touching it, with the
    // surface-normal component removed so resting ships aren't glued to the belt
    private fun tangentialSlip(slip: Vector3dc, normalWorld: Vector3dc?): Vector3d {
        val result = Vector3d(slip)
        if (normalWorld != null) {
            val normal = Vector3d(normalWorld)
            result.sub(normal.mul(result.dot(normal)))
        }
        return result
    }

    // Solves K * F = desiredDeltaV, where K is the effective mass matrix of the
    // body at the contact point. This is the exact force that produces the wanted
    // point velocity change over one physics tick, including the torque it takes -
    // same idea the turntable uses, just in full 3D
    private fun solveStickingForce(body: PhysShip, worldPos: Vector3dc, desiredDeltaV: Vector3dc, scale: Double): Vector3d {
        val mass = body.mass
        val comWorld = body.transform.shipToWorld.transformPosition(Vector3d(body.centerOfMass))
        val r = Vector3d(worldPos).sub(comWorld)

        val skew = crossMatrix(r)
        val rot = Matrix3d().set(body.transform.shipToWorldRotation)
        val inertiaWorldInv = Matrix3d(rot)
            .mul(Matrix3d(body.momentOfInertia).invert())
            .mul(rot.transpose(Matrix3d()))

        // K = (1/m)I - skew * I^-1 * skew
        val effectiveMass = Matrix3d()
            .scale(1.0 / mass)
            .sub(Matrix3d(skew).mul(inertiaWorldInv).mul(skew))

        val det = effectiveMass.determinant()
        return if (det.isFinite() && kotlin.math.abs(det) > 1e-12) {
            effectiveMass.invert().transform(Vector3d(desiredDeltaV).mul(scale))
        } else {
            // Degenerate matrix (shouldn't happen, K is positive definite) - fall back
            // to a plain linear impulse
            Vector3d(desiredDeltaV).mul(scale * mass)
        }
    }

    private fun crossMatrix(r: Vector3dc): Matrix3d {
        return Matrix3d()
            .setColumn(0, 0.0, r.z(), -r.y())
            .setColumn(1, -r.z(), 0.0, r.x())
            .setColumn(2, r.y(), -r.x(), 0.0)
    }

    private fun clampSlip(slip: Vector3d): Vector3d {
        if (slip.x.isNaN() || slip.y.isNaN() || slip.z.isNaN()) return Vector3d()
        val length = slip.length()
        if (length > MAX_SLIP) slip.mul(MAX_SLIP / length)
        return slip
    }

    private fun pointVelocity(ship: PhysShip, worldPos: Vector3dc): Vector3d {
        val comWorld = ship.transform.shipToWorld.transformPosition(Vector3d(ship.centerOfMass))
        val radius = Vector3d(worldPos).sub(comWorld)
        return Vector3d(ship.velocity).add(Vector3d(ship.angularVelocity).cross(radius, Vector3d()))
    }

    private fun findContacts(
        level: ServerLevel,
        pos: BlockPos,
        state: BlockState,
        holderShip: LoadedServerShip?
    ): List<BeltContact> {
        val contacts = mutableListOf<BeltContact>()
        val slope = state.getValue(BeltBlock.SLOPE)

        val collisionAABB = state.getCollisionShape(level, pos).bounds()
        val regions: List<ContactRegion> = if (slope == BeltSlope.HORIZONTAL) {
            val minX = collisionAABB.minX - LATERAL_EPS
            val maxX = collisionAABB.maxX + LATERAL_EPS
            val minZ = collisionAABB.minZ - LATERAL_EPS
            val maxZ = collisionAABB.maxZ + LATERAL_EPS
            listOf(
                ContactRegion(
                    AABB(
                        minX, collisionAABB.maxY, minZ,
                        maxX, collisionAABB.maxY + CONTACT_EPS, maxZ
                    ).move(pos), 1, Vector3d(0.0, 1.0, 0.0)
                ),
                ContactRegion(
                    AABB(
                        minX, collisionAABB.minY - CONTACT_EPS, minZ,
                        maxX, collisionAABB.minY, maxZ
                    ).move(pos), -1, Vector3d(0.0, -1.0, 0.0)
                )
            )
        } else {
            // Sloped, vertical and sideways belts use a single all-around region
            listOf(ContactRegion(collisionAABB.inflate(CONTACT_EPS).move(pos), 1, null))
        }

        for (region in regions) {
            val worldRegion = toWorld(region.aabb, holderShip)

            // Ships resting against the belt
            for (ship in level.getShipsIntersecting(worldRegion)) {
                if (holderShip != null && ship.id == holderShip.id) continue
                if (ship !is ServerShip) continue

                val intersection = intersect(worldRegion, ship.worldAABB) ?: continue
                val worldPoint = intersection.center
                val localPoint = if (holderShip != null) {
                    holderShip.worldToShip.transformPosition(Vector3d(worldPoint.x, worldPoint.y, worldPoint.z))
                } else {
                    Vector3d(worldPoint.x, worldPoint.y, worldPoint.z)
                }
                contacts.add(BeltContact(ship.id, localPoint, region.sign, region.normalLocal, contactArea(intersection)))
            }

            // The static world touching a belt that sits on a ship (treads)
            if (holderShip != null) {
                val worldToShip = holderShip.worldToShip
                for (cell in BlockPos.betweenClosed(
                    worldRegion.minX.toInt(), worldRegion.minY.toInt(), worldRegion.minZ.toInt(),
                    worldRegion.maxX.toInt(), worldRegion.maxY.toInt(), worldRegion.maxZ.toInt()
                )) {
                    if (!level.isLoaded(cell)) continue
                    val cellState = level.getBlockState(cell)
                    if (cellState.getCollisionShape(level, cell).isEmpty) continue

                    val cellAABB = cellState.getCollisionShape(level, cell).bounds().move(cell)
                    val intersection = intersect(worldRegion, cellAABB) ?: continue
                    val worldPoint = intersection.center
                    val localPoint = worldToShip.transformPosition(Vector3d(worldPoint.x, worldPoint.y, worldPoint.z))
                    contacts.add(BeltContact(WORLD_ID, localPoint, region.sign, region.normalLocal, contactArea(intersection)))
                }
            }
        }

        return contacts
    }

    private fun toWorld(region: AABB, holderShip: LoadedServerShip?): AABB {
        if (holderShip == null) return region
        val world = AABBd(region.minX, region.minY, region.minZ, region.maxX, region.maxY, region.maxZ)
            .transform(holderShip.shipToWorld, AABBd())
        return AABB(world.minX(), world.minY(), world.minZ(), world.maxX(), world.maxY(), world.maxZ())
    }

    // Rough measure of how much belt surface a contact covers, used to pick the
    // winning belt when several touch the same ship
    private fun contactArea(box: AABB): Double {
        val dx = box.maxX - box.minX
        val dy = box.maxY - box.minY
        val dz = box.maxZ - box.minZ
        return max(dx * dz, max(dx * dy, dy * dz))
    }

    private fun intersect(a: AABB, b: AABB): AABB? {
        val minX = max(a.minX, b.minX)
        val minY = max(a.minY, b.minY)
        val minZ = max(a.minZ, b.minZ)
        val maxX = min(a.maxX, b.maxX)
        val maxY = min(a.maxY, b.maxY)
        val maxZ = min(a.maxZ, b.maxZ)
        if (maxX - minX <= 0.0 || maxY - minY <= 0.0 || maxZ - minZ <= 0.0) return null
        return AABB(minX, minY, minZ, maxX, maxY, maxZ)
    }

    private fun intersect(a: AABB, b: AABBdc): AABB? {
        return intersect(
            a,
            AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())
        )
    }
}
