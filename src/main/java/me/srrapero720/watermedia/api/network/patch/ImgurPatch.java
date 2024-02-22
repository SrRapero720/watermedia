package me.srrapero720.watermedia.api.network.patch;

import me.srrapero720.watermedia.api.network.DynamicURL;
import me.srrapero720.watermedia.tools.exceptions.PatchingURLException;

public class ImgurPatch extends URLPatch {
    @Override
    public String platform() {
        return "Imgur";
    }

    @Override
    public boolean isValid(DynamicURL dynamicURL) {
        return dynamicURL.asURL().getHost().equals("imgur.com"); // i.imgur.com is a working url
    }

    @Override
    public DynamicURL patch(DynamicURL dynamicURL, Quality preferQuality) throws PatchingURLException {
        super.patch(dynamicURL, preferQuality);
        try {
//            String path = url.getPath();
//            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
//
//            // URL DATA
//            String[] ps = path.split("/");
//            String id = ps[ps.length - 1];
//            String tag = ps[ps.length - 2];
//
//            if (path.startsWith("/gallery/") || path.startsWith("/a/")) {
//                Response<ImgurData<ImgurAlbumTagData>> res = ImgurAPIv3.NET.getImageFromAlbum(id).execute();
//                ImgurAlbumTagData data;
//                if (res.isSuccessful() && res.body() != null && (data = res.body().data) != null && !data.images.isEmpty()) {
//                    for (ImgurImage image: data.images) {
//                        // TODO: add support for multiple entries
//                        return new Result(image.link, image.type.startsWith("video"), false);
//                    }
//                } else {
//                    LOGGER.debug(IT, "Imgur responses with status code: {}", res.code());
//                    throw new Exception("Cannot load imgur data, " + res.message());
//                }
//            } else if (path.startsWith("/t/")) {
//                Response<ImgurData<ImgurAlbumTagData>> res = ImgurAPIv3.NET.getImageFromTagGallery(tag, id).execute();
//                ImgurAlbumTagData data;
//                if (res.isSuccessful() && res.body() != null && (data = res.body().data) != null) {
//                    if (data.images != null && !data.images.isEmpty()) {
//                        for (ImgurImage image: data.images) {
//                            // TODO: add support for multiple entries
//                            return new Result(image.link, image.type.startsWith("video"), false);
//                        }
//                    } else {
//                        LOGGER.debug(IT, "Imgur responses with status code '{}' but with a empty image list", res.code());
//                        throw new Exception("Cannot load imgur data, empty list");
//                    }
//                } else {
//                    LOGGER.debug(IT, "Imgur responses with status code: {}", res.code());
//                    throw new Exception("Cannot load imgur data, " + res.message());
//                }
//            } else {
//                Response<ImgurData<ImgurImage>> res = ImgurAPIv3.NET.getImage(id).execute();
//                ImgurImage data;
//                if (res.isSuccessful() && res.body() != null && (data = res.body().data) != null) {
//                    return new Result(data.link, data.type.startsWith("video"), false);
//                } else {
//                    LOGGER.debug(IT, "Imgur responses with status code: {}", res.code());
//                    throw new Exception("Cannot load imgur data, " + res.message());
//                }
//            }
            throw new Exception("Unreachable code");
        } catch (Exception e) {
            throw new PatchingURLException(dynamicURL, e);
        }
    }
}