package dev.myriadclient.impl.service;

import dev.myriadclient.api.service.Enemies;

import java.nio.file.Path;

/** Your enemies, stored in {@code myriad/enemies.json}. */
public final class EnemiesManager extends NameList implements Enemies {
	public EnemiesManager(Path root) {
		super(root, "enemies.json");
	}

	@Override
	public boolean isEnemy(String name) {
		return contains(name);
	}
}
