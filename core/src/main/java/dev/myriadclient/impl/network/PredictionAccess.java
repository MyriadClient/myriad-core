package dev.myriadclient.impl.network;

/** Implemented on ClientLevel by mixin: the sequence number of the latest block action the client predicted. */
public interface PredictionAccess {
	int myriad$currentSequence();
}
