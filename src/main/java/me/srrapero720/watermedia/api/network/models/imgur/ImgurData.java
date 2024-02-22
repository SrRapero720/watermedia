package me.srrapero720.watermedia.api.network.models.imgur;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class ImgurData<T> {
    @SerializedName("data")
    @Expose
    public T data;
}