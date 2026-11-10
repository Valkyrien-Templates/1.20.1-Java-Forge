# Counter Clockwise
A new VSxCreate addon that extends VS functionality to base Create!
 
## Planned Features
- [x] Belts as Tracks
- [ ] Turntables turn Ships
- [x] Fans push/pull Ships
- [x] Mechanical arms between ships
- [x] Portable Interfaces
  - [x] Ship -> World
  - [x] Ship -> Ship
  - [x] World -> Ship
- [x] Stickers can constraint (done except particles + sounds)
- [ ] Water Wheels propel Ships
  - [ ] Water-based Propulsion (Steamboat)
  - [ ] Land-based (Wheels)
- [ ] Cogs as Wheels
- [ ] Fly***wheels***
- [ ] Pump-based Propulsion
  - Pumps underwater propel Ships

## Credits

The code for the storage interface, sticker, and mechanical arm comes from
VS addition, which is licensed under MIT. Some changes have been made, but credit
for the main logic goes to xwzy.

Packages/files that use VS addition code:
- `[java].mixin.create.mechanical_arm.*`
- `[java].mixin.create.psi.*`
- `[java].mixin.create.sticker.*`
- `[java].mixinducks.create.mechanical_arm.*`
- `[java].mixinducks.create.portable_interface.*`
- `[kotlin].create.psi.*`
- `[kotlin].create.sticker.*`
- `[kotlin].ships.JointGroup`
- `[kotlin].ships.JointManager`
