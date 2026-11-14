package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.EmptyState;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;

/** The Friends window: your friends listed, with a field to add one and a button beside each to remove them. */
public final class FriendsPanel extends WidgetPanel {
	private String name = "";

	@Override
	public String title() {
		return "Friends";
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	protected void build(VBox content) {
		HBox add = new HBox(4);
		add.addWeighted(new TextField(() -> name).placeholder("Player name").onChange(s -> name = s).onSubmit(s -> addFriend()), 1);
		add.addFixed(new Button("Add", this::addFriend).accent(), 36);
		content.add(add);
		if (Myriad.friends().all().isEmpty()) content.add(new EmptyState(Icons.USERS, "No friends yet", "Modules like Kill Aura skip friends."));
		for (String f : Myriad.friends().all()) {
			HBox row = new HBox(4);
			row.addWeighted(new Label(f), 1);
			row.addFixed(new Button("Remove", () -> {
				Myriad.friends().remove(f);
				rebuild();
			}), 50);
			content.add(row);
		}
	}

	private void addFriend() {
		if (!name.isBlank() && Myriad.friends().add(name.trim())) {
			name = "";
			rebuild();
		}
	}
}
