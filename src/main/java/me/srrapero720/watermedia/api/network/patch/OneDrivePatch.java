package me.srrapero720.watermedia.api.network.patch;

import me.srrapero720.watermedia.api.network.DynamicRequest;
import me.srrapero720.watermedia.api.network.DynamicURL;
import me.srrapero720.watermedia.api.network.NetworkAPI;
import me.srrapero720.watermedia.api.network.models.onedrive.OneDriveItem;
import me.srrapero720.watermedia.tools.exceptions.PatchingURLException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Base64;
import java.util.regex.Pattern;

public class OneDrivePatch extends URLPatch {
    private static final Pattern ONE_DRIVE_URL_PATTERN = Pattern.compile("^https://1drv.ms/[a-z]/[a-zA-Z0-9!_-]+$");
    private static final String API_URL = "https://api.onedrive.com/v1.0/";

    @Override
    public String platform() {
        return "OneDrive";
    }

    @Override
    public boolean isValid(DynamicURL dynamicURL) {
        return ONE_DRIVE_URL_PATTERN.matcher(dynamicURL.toString()).matches();
    }

    @Override
    public DynamicURL patch(DynamicURL dynamicURL, Quality prefQuality) throws PatchingURLException {
        super.patch(dynamicURL, prefQuality);
        final String encodedUrl = "u!" + Base64.getUrlEncoder().withoutPadding().encodeToString(dynamicURL.getSource().getBytes());
        try (DynamicRequest request = new DynamicRequest(new DynamicURL(API_URL + "shares/" + encodedUrl + "/driveItem"))) {

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
                final OneDriveItem item = NetworkAPI.GSON.fromJson(reader, OneDriveItem.class);
                return new DynamicURL(item.getUrl(), false, false);
            }
        } catch (Exception e) {
            throw new PatchingURLException(dynamicURL.getSource(), e);
        }
    }
}