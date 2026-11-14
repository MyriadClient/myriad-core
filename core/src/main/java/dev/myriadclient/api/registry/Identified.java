package dev.myriadclient.api.registry;

import dev.myriadclient.api.registry.MyriadId;

/** Anything that lives in a {@link Registry}: it has an id, unique in its registry. */
public interface Identified {
	/** The id, {@code <mod id of the addon that registered it>:<path>}; it doesn't change once registered. */
	MyriadId id();
}
