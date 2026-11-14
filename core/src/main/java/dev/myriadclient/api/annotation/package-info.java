/**
 * The threading contract, as annotations on API types and methods: {@link dev.myriadclient.api.annotation.RenderThread}
 * (call it on the game's own thread only, the default for everything) and
 * {@link dev.myriadclient.api.annotation.AnyThread} (safe from any thread, or an event that may arrive off it).
 */
@NullMarked
package dev.myriadclient.api.annotation;

import org.jspecify.annotations.NullMarked;
