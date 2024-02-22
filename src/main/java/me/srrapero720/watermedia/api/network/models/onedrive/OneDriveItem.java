package me.srrapero720.watermedia.api.network.models.onedrive;

import com.google.gson.annotations.SerializedName;

public class OneDriveItem {
        private String id;
        private String name;
        private long size;
        @SerializedName("@content.downloadUrl")
        private String url;

        public OneDriveItem() {}

        public OneDriveItem(String id, String name, long size, String url) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.url = url;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public long getSize() {
            return size;
        }

        public String getUrl() {
            return url;
        }
    }