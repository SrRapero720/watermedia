# WATER PROTOCOL SPECIFICATION
`water://` is a URI scheme designed to efficiently retrieve media content from diferent sources without requesting
extra arguments on the current API.

## URI Structure
A `water://` URI consists of the following components:
```water://<source_type>/<media_id>```
- `<source_type>`: Indicates the type of source from which the media is to be retrieved. Supported source types are}
  - local: the current working directory of the application, e.g., `water://local/config/song.mp3` translates into `./config/song.mp3`
  - remote: fetchs the file from the remote server, e.g., `water://remote/xyz12345.mp4`, where `xyz12345` is the media ID on the remote server, it never uses paths.
  - global: fetchs the file from the global server (hosted by WaterMediaTeam), e.g., `water://global/abcd6789.jpg`, where `abcd6789` is the media ID in the global server.

- `<media_id>`: A unique identifier for the media content. The format of the media ID may vary depending on the source type.


## Examples
- Local File: `water://local/images/photo.png` retrieves the file `photo.png` from the `images` directory in the current working directory.
- Remote File: `water://remote/xyz12345.mp4` retrieves the media file with ID `xyz12345` from the remote server.
- Global File: `water://global/abcd6789.jpg` retrieves the media file with ID `abcd6789` from the global server.

## Important Notes
Remote and Global source types do not support file paths; only media IDs are used to identify the content.

