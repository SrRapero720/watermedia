package me.srrapero720.watermedia.api.network.models.kick;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;


public class KickVideo implements Serializable {
    @SerializedName("id")
    @Expose
    public int id;

    @SerializedName("live_stream_id")
    @Expose
    public int streamId;

    @SerializedName("source")
    @Expose
    public String url;
}
