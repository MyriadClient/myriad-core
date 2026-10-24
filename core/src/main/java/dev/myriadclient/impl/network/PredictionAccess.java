package dev.myriadclient.impl.network;

/** Implemented on ClientLevel by mixin: the client's block predictions. */
public interface PredictionAccess {
	/** The sequence number of the latest block action the client predicted. */
	int myriad$currentSequence();

	/** Puts every block predicted up to {@code sequence} back as the server last had it, as an acknowledgement would. */
	void myriad$endPredictionsUpTo(int sequence);
}
