# APS | Animated Picture Stream Specification
APS is a container format for images an audio, focused on begin 
smart textures and animated images distribution.

# Block Structure
- Block ID (1 byte) + Size (4 bytes unsigned) + Data (Length match size) (contains the actual data of the block)

# Structure
- Headers Signature + Version (4 bytes): "APS1"
- Metadata Block data (optional, order is important, separated by commas, empty fields allowed), example `(Cute Ducks,John Doe,A picture of cute ducks,2023-10-05,ducks-cute-birds,CC-BY)`:
  - Title
  - Author
  - Description
  - Creation Date
  - Tags (separated by semicolons)
  - License SPDX
- Image Block Data
  - Dimensions (Width, Height) (2 bytes each unsigned)
  - Color Space (2 bits): 0 = RGB, 1 = YUV, 2 = Grayscale, 3 = Indexed
  - Compression Type (2 bits): 0 = RAW/INDEXED, 1 = DEFLATE, 2 = LZ4
  - 4 bits reserved
  - Frame Count (4 bits unsigned)
- Image Palette Block Data (optional, only if Color Space = Indexed)
  - Palette Size (2 bytes unsigned)
  - Data
- Image Frames Block Data (repeated for each frame, sum delays must match total duration)
  - Frame delay (4 bytes unsigned)
  - Disposal Method (1 byte): 0 = None, 1 = Background, 2 = Previous
  - Data
- Audio Block (optional)
  - Repeat Flag (1 byte): -2 Repeat Infinite, -1 = Repeat On Image Start, 0 = No Repeat, >1 = Repeat Count
  - Duration (4 bytes unsigned)
  - Sample Rate (2 bytes unsigned)
  - Channels (1 byte unsigned)
  - Bit Depth (1 byte unsigned)
  - Data