package me.srrapero720.watermedia.api.network.models.imgur.images;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class ImgurImage {
    @SerializedName("id")
    @Expose
    public String id;

    @SerializedName("type")
    @Expose
    public String type;

    @SerializedName("width")
    @Expose
    public int width;

    @SerializedName("height")
    @Expose
    public int height;

    @SerializedName("size")
    @Expose
    public int size;

    @SerializedName("link")
    @Expose
    public String link;

    @SerializedName("hls")
    @Expose
    public String hls;
}