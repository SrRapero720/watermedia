/**
 * NetworkAPI
 * <p>It provides the static file server on a custom port and the uploads to it. The server stays off until you turn it
 * on in the config, and it only works through manual configuration syncing: the server and its clients must share the
 * same {@code network.token}, and {@code network.remoteUrl} must point to the server's address and port.</p>
 */
package org.watermedia.api.network;