package me.srrapero720.watermedia.api.network;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;

public class DynamicRequest implements Closeable {
    private final HttpURLConnection connection;
    private final String requestMethod;
    public DynamicRequest(DynamicURL dynamicURL) throws IOException {
        this(dynamicURL, "GET");
    }
    public DynamicRequest(DynamicURL dynamicURL, String requestMethod) throws IOException {
        this.connection = (HttpURLConnection) dynamicURL.asURL().openConnection();
        this.requestMethod = requestMethod;
    }

    public DynamicRequest setRequestProperty(String key, String value) {
        this.connection.setRequestProperty(key, value);
        return this;
    }

    public InputStream getInputStream() throws IOException {
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            String msg = String.format("Server response with status code (%s): %s", this.connection.getResponseCode(), this.connection.getResponseMessage());
            this.connection.setRequestMethod(requestMethod);
            this.close();
            throw new ConnectException(msg);
        }
        return connection.getInputStream();
    }

    public int getResponseCode() throws IOException {
        return this.connection.getResponseCode();
    }

    @Override
    public void close() throws IOException {
        this.connection.disconnect();
    }
}
