package me.srrapero720.watermedia.tools.exceptions;

import me.srrapero720.watermedia.api.network.DynamicURL;

import java.net.URL;

public class PatchingURLException extends Exception {
    public PatchingURLException(String url, Throwable t) { super("Failed to patch URL: " + url, t); }
    public PatchingURLException(URL url, Throwable t) { super("Failed to patch URL: " + url.toString(), t); }
    public PatchingURLException(DynamicURL url, Throwable t) { super("Failed to patch URL: " + url.getSource(), t); }
}