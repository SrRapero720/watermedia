package me.srrapero720.watermedia.api.network.models.imgur;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import me.srrapero720.watermedia.api.network.models.imgur.images.ImgurImage;

import java.util.List;

public class ImgurAlbumTagData {
    @SerializedName("id")
    @Expose
    public String id;

    @SerializedName("cover")
    @Expose
    public String coverId;

    @SerializedName("nsfw")
    @Expose
    public boolean nsfw;

    @SerializedName("images_count")
    @Expose
    public int imageCount;

    @SerializedName("images")
    @Expose
    public List<ImgurImage> images;


}