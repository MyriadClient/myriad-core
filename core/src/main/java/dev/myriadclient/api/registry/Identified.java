package dev.myriadclient.api.registry;

import dev.myriadclient.api.util.MyriadId;

/** Anything that lives in a {@link Registry}. */
public interface Identified {
	MyriadId id();
}
