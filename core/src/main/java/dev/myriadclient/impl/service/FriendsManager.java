package dev.myriadclient.impl.service;

import dev.myriadclient.api.service.Friends;

import java.nio.file.Path;

/** Your friends, stored in {@code myriad/friends.json}. */
public final class FriendsManager extends NameList implements Friends {
	public FriendsManager(Path root) {
		super(root, "friends.json");
	}

	@Override
	public boolean isFriend(String name) {
		return contains(name);
	}
}
