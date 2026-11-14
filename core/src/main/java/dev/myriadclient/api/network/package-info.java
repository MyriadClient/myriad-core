/**
 * Talking to the server and the web: {@link dev.myriadclient.api.network.Packets} sends packets (silently, or counted
 * against the server's limits through {@code Myriad.limits()}), {@link dev.myriadclient.api.network.Http} makes web
 * requests off the render thread. Reading packets as they arrive or leave is {@code PacketEvent}.
 */
@NullMarked
package dev.myriadclient.api.network;

import org.jspecify.annotations.NullMarked;
