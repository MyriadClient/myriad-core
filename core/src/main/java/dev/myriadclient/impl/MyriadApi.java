package dev.myriadclient.impl;

/**
 * Where Myriad's web API and website are: {@code api.myriadclient.dev} and {@code myriadclient.dev}, or
 * {@code -Dmyriad.api} and {@code -Dmyriad.site} for a local one. Pointing them elsewhere never changes what's trusted:
 * shares are only data, the Library's index is checked against Myriad's root keys wherever it comes from, and a saved
 * Myriad session is only sent to the API it was made with.
 */
public final class MyriadApi {
	public static final String API = strip(System.getProperty("myriad.api", "https://api.myriadclient.dev"));
	public static final String SITE = strip(System.getProperty("myriad.site", "https://myriadclient.dev"));

	private MyriadApi() {
	}

	private static String strip(String s) {
		return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}
}
