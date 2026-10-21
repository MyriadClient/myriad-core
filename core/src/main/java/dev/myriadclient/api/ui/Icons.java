package dev.myriadclient.api.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ready-made icons: glyphs of the icon font the UI draws with (Font Awesome, in the bundled Nerd Font), each with a name
 * and words to find it by. Use the constants anywhere an icon string goes (a module group, a tag, a panel), or let
 * players choose with an {@link dev.myriadclient.api.setting.IconSetting} and its picker.
 */
public final class Icons {
	/** One icon: its glyph, a name, the section it's listed under, and more words to find it by. */
	public record Icon(String glyph, String name, String section, String keywords) {
		/** Whether every word of {@code query} starts a word of the name, section or keywords. */
		public boolean matches(String query) {
			String hay = " " + (name + " " + section + " " + keywords).toLowerCase(Locale.ROOT).replace('-', ' ');
			for (String w : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!w.isEmpty() && !hay.contains(" " + w)) return false;
			return true;
		}
	}

	private static final List<Icon> ALL = new ArrayList<>();

	// Combat
	public static final String CROSSHAIRS = add("\uf05b", "Crosshairs", "Combat", "aim target combat");
	public static final String BULLSEYE = add("\uf140", "Bullseye", "Combat", "target aim");
	public static final String SHIELD = add("\uf132", "Shield", "Combat", "defence armor protect safe");
	public static final String BOMB = add("\uf1e2", "Bomb", "Combat", "explode tnt crystal");
	public static final String GAVEL = add("\uf0e3", "Hammer", "Combat", "gavel hit smash");
	public static final String FIGHTER_JET = add("\uf0fb", "Jet", "Combat", "fast attack");
	public static final String HEARTBEAT = add("\uf21e", "Heartbeat", "Combat", "health pulse");
	public static final String MEDKIT = add("\uf0fa", "Medkit", "Combat", "heal health totem");
	public static final String USER_SECRET = add("\uf21b", "Spy", "Combat", "stealth hidden incognito");
	public static final String BAN = add("\uf05e", "Ban", "Combat", "block forbid no");

	// Movement
	public static final String ROCKET = add("\uf135", "Rocket", "Movement", "firework boost launch");
	public static final String PLANE = add("\uf072", "Plane", "Movement", "fly elytra travel");
	public static final String PAPER_PLANE = add("\uf1d8", "Paper plane", "Movement", "send fly glide");
	public static final String SPACE_SHUTTLE = add("\uf197", "Shuttle", "Movement", "fly space");
	public static final String CAR = add("\uf1b9", "Car", "Movement", "drive vehicle");
	public static final String TRUCK = add("\uf0d1", "Truck", "Movement", "vehicle haul");
	public static final String BICYCLE = add("\uf206", "Bicycle", "Movement", "ride");
	public static final String SHIP = add("\uf21a", "Ship", "Movement", "boat sail");
	public static final String ANCHOR = add("\uf13d", "Anchor", "Movement", "hold stay");
	public static final String TRAIN = add("\uf238", "Train", "Movement", "rail minecart");
	public static final String ROAD = add("\uf018", "Road", "Movement", "highway path");
	public static final String LOCATION_ARROW = add("\uf124", "Location arrow", "Movement", "navigate direction");
	public static final String COMPASS = add("\uf14e", "Compass", "Movement", "navigate direction");
	public static final String STREET_VIEW = add("\uf21d", "Person", "Movement", "walk player");
	public static final String ARROWS = add("\uf047", "Arrows", "Movement", "move all directions");
	public static final String ARROW_UP = add("\uf062", "Arrow up", "Movement", "up");
	public static final String ARROW_DOWN = add("\uf063", "Arrow down", "Movement", "down");
	public static final String ARROW_LEFT = add("\uf060", "Arrow left", "Movement", "left back");
	public static final String ARROW_RIGHT = add("\uf061", "Arrow right", "Movement", "right forward");
	public static final String LEVEL_UP = add("\uf148", "Level up", "Movement", "ascend climb");
	public static final String LEVEL_DOWN = add("\uf149", "Level down", "Movement", "descend");
	public static final String ARROWS_V = add("\uf07d", "Up and down", "Movement", "vertical");
	public static final String ARROWS_H = add("\uf07e", "Left and right", "Movement", "horizontal");

	// Render
	public static final String EYE = add("\uf06e", "Eye", "Render", "see view esp render");
	public static final String EYE_SLASH = add("\uf070", "Eye slash", "Render", "hide invisible");
	public static final String BINOCULARS = add("\uf1e5", "Binoculars", "Render", "zoom look far");
	public static final String SEARCH = add("\uf002", "Search", "Render", "find magnify");
	public static final String LIGHTBULB = add("\uf0eb", "Light bulb", "Render", "bright idea fullbright");
	public static final String SUN = add("\uf185", "Sun", "Render", "day bright");
	public static final String MOON = add("\uf186", "Moon", "Render", "night dark");
	public static final String PAINT_BRUSH = add("\uf1fc", "Paint brush", "Render", "theme colour");
	public static final String EYEDROPPER = add("\uf1fb", "Eyedropper", "Render", "colour pick");
	public static final String TINT = add("\uf043", "Drop", "Render", "water colour tint");
	public static final String ADJUST = add("\uf042", "Contrast", "Render", "adjust half");
	public static final String CAMERA = add("\uf030", "Camera", "Render", "screenshot photo");
	public static final String VIDEO = add("\uf03d", "Video camera", "Render", "record");
	public static final String PICTURE = add("\uf03e", "Picture", "Render", "image");
	public static final String FILM = add("\uf008", "Film", "Render", "movie");
	public static final String OBJECT_GROUP = add("\uf247", "Object group", "Render", "outline select box");
	public static final String CLONE = add("\uf24d", "Clone", "Render", "copy duplicate ghost");

	// Player
	public static final String USER = add("\uf007", "User", "Player", "player person");
	public static final String USERS = add("\uf0c0", "Users", "Player", "friends players group");
	public static final String USER_PLUS = add("\uf234", "Add user", "Player", "friend add");
	public static final String CHILD = add("\uf1ae", "Child", "Player", "small");
	public static final String HAND_PAPER = add("\uf256", "Hand", "Player", "stop open");
	public static final String HAND_ROCK = add("\uf255", "Fist", "Player", "punch grab");
	public static final String HAND_POINTER = add("\uf25a", "Pointer hand", "Player", "click use");
	public static final String MOUSE_POINTER = add("\uf245", "Mouse pointer", "Player", "click cursor");
	public static final String THUMBS_UP = add("\uf164", "Thumbs up", "Player", "like ok");
	public static final String SMILE = add("\uf118", "Smile", "Player", "happy emote");
	public static final String CUTLERY = add("\uf0f5", "Cutlery", "Player", "food eat");
	public static final String COFFEE = add("\uf0f4", "Coffee", "Player", "drink afk break");
	public static final String BEER = add("\uf0fc", "Mug", "Player", "drink potion");
	public static final String FLASK = add("\uf0c3", "Flask", "Player", "potion brew");
	public static final String BED = add("\uf236", "Bed", "Player", "sleep spawn");
	public static final String SHOPPING_CART = add("\uf07a", "Cart", "Player", "buy trade");
	public static final String SHOPPING_BAG = add("\uf290", "Bag", "Player", "inventory items");
	public static final String SUITCASE = add("\uf0f2", "Suitcase", "Player", "carry inventory");
	public static final String BRIEFCASE = add("\uf0b1", "Briefcase", "Player", "work kit");

	// World
	public static final String CUBE = add("\uf1b2", "Cube", "World", "block build");
	public static final String CUBES = add("\uf1b3", "Cubes", "World", "blocks build schematic");
	public static final String WRENCH = add("\uf0ad", "Wrench", "World", "tool fix repair");
	public static final String MAGIC = add("\uf0d0", "Wand", "World", "magic misc");
	public static final String FIRE = add("\uf06d", "Fire", "World", "flame burn lava");
	public static final String LEAF = add("\uf06c", "Leaf", "World", "nature plant farm");
	public static final String TREE = add("\uf1bb", "Tree", "World", "nature wood");
	public static final String SNOWFLAKE = add("\uf2dc", "Snowflake", "World", "ice cold snow");
	public static final String CLOUD = add("\uf0c2", "Cloud", "World", "weather sky");
	public static final String BOLT = add("\uf0e7", "Bolt", "World", "lightning fast power redstone");
	public static final String UMBRELLA = add("\uf0e9", "Umbrella", "World", "rain weather");
	public static final String GLOBE = add("\uf0ac", "Globe", "World", "world earth server");
	public static final String MAP = add("\uf279", "Map", "World", "world");
	public static final String MAP_MARKER = add("\uf041", "Map marker", "World", "waypoint location");
	public static final String MAP_PIN = add("\uf276", "Map pin", "World", "waypoint");
	public static final String MAP_SIGNS = add("\uf277", "Signs", "World", "direction");
	public static final String HOME = add("\uf015", "Home", "World", "base house");
	public static final String BUILDING = add("\uf1ad", "Building", "World", "base city");
	public static final String INDUSTRY = add("\uf275", "Factory", "World", "farm machine");
	public static final String UNIVERSITY = add("\uf19c", "Temple", "World", "monument");
	public static final String DIAMOND = add("\uf219", "Diamond", "World", "gem ore valuable");
	public static final String MAGNET = add("\uf076", "Magnet", "World", "pull collect pickup");
	public static final String RECYCLE = add("\uf1b8", "Recycle", "World", "reuse loop");
	public static final String ARCHIVE = add("\uf187", "Box", "World", "shulker chest storage");
	public static final String INBOX = add("\uf01c", "Inbox", "World", "chest container");
	public static final String DATABASE = add("\uf1c0", "Database", "World", "storage data");
	public static final String KEY = add("\uf084", "Key", "World", "unlock open");
	public static final String LOCK = add("\uf023", "Lock", "World", "locked secure");
	public static final String UNLOCK = add("\uf09c", "Unlock", "World", "open");
	public static final String BUG = add("\uf188", "Bug", "World", "insect debug");
	public static final String PAW = add("\uf1b0", "Paw", "World", "animal pet");

	// Tools
	public static final String COG = add("\uf013", "Gear", "Tools", "settings options");
	public static final String COGS = add("\uf085", "Gears", "Tools", "settings machine");
	public static final String SLIDERS = add("\uf1de", "Sliders", "Tools", "controls adjust");
	public static final String TACHOMETER = add("\uf0e4", "Gauge", "Tools", "speed performance");
	public static final String TERMINAL = add("\uf120", "Terminal", "Tools", "command console");
	public static final String CODE = add("\uf121", "Code", "Tools", "script dev");
	public static final String KEYBOARD = add("\uf11c", "Keyboard", "Tools", "keys bind type");
	public static final String GAMEPAD = add("\uf11b", "Gamepad", "Tools", "game play");
	public static final String PLUG = add("\uf1e6", "Plug", "Tools", "connect hook addon");
	public static final String PUZZLE = add("\uf12e", "Puzzle piece", "Tools", "addon extension");
	public static final String MICROCHIP = add("\uf2db", "Chip", "Tools", "cpu computer");
	public static final String SERVER = add("\uf233", "Server", "Tools", "host");
	public static final String POWER = add("\uf011", "Power", "Tools", "on off toggle");
	public static final String REFRESH = add("\uf021", "Refresh", "Tools", "reload sync");
	public static final String UNDO = add("\uf0e2", "Undo", "Tools", "back revert");
	public static final String REPEAT = add("\uf01e", "Repeat", "Tools", "loop again");
	public static final String RANDOM = add("\uf074", "Shuffle", "Tools", "random mix");
	public static final String EXCHANGE = add("\uf0ec", "Swap", "Tools", "exchange switch");
	public static final String RETWEET = add("\uf079", "Cycle", "Tools", "loop swap");
	public static final String HISTORY = add("\uf1da", "History", "Tools", "past recent");
	public static final String CLOCK = add("\uf017", "Clock", "Tools", "time timer");
	public static final String HOURGLASS = add("\uf254", "Hourglass", "Tools", "wait delay");
	public static final String CALENDAR = add("\uf073", "Calendar", "Tools", "date");
	public static final String CALCULATOR = add("\uf1ec", "Calculator", "Tools", "math");
	public static final String SITEMAP = add("\uf0e8", "Tree diagram", "Tools", "structure layout");
	public static final String FILTER = add("\uf0b0", "Filter", "Tools", "sort funnel");
	public static final String SORT = add("\uf0dc", "Sort", "Tools", "order");
	public static final String LIST = add("\uf03a", "List", "Tools", "items");
	public static final String TH = add("\uf00a", "Grid", "Tools", "tiles layout");
	public static final String BARS = add("\uf0c9", "Menu", "Tools", "hamburger list");
	public static final String DOT_CIRCLE = add("\uf192", "Wheel", "Tools", "circle pick ring");
	public static final String WINDOW = add("\uf2d0", "Window", "Tools", "panel open");
	public static final String SAVE = add("\uf0c7", "Save", "Tools", "disk store");
	public static final String COPY = add("\uf0c5", "Copy", "Tools", "duplicate");
	public static final String PASTE = add("\uf0ea", "Clipboard", "Tools", "paste");
	public static final String SCISSORS = add("\uf0c4", "Scissors", "Tools", "cut");
	public static final String PENCIL = add("\uf040", "Pencil", "Tools", "edit write");
	public static final String ERASER = add("\uf12d", "Eraser", "Tools", "clear delete");
	public static final String TRASH = add("\uf1f8", "Bin", "Tools", "delete remove");
	public static final String FOLDER = add("\uf07b", "Folder", "Tools", "files");
	public static final String LINK = add("\uf0c1", "Link", "Tools", "chain connect");
	public static final String SHARE = add("\uf1e0", "Share", "Tools", "send");
	public static final String DOWNLOAD = add("\uf019", "Download", "Tools", "get save");
	public static final String UPLOAD = add("\uf093", "Upload", "Tools", "send publish");

	// Signs
	public static final String STAR = add("\uf005", "Star", "Signs", "favourite");
	public static final String HEART = add("\uf004", "Heart", "Signs", "love health");
	public static final String BELL = add("\uf0f3", "Bell", "Signs", "notify alert");
	public static final String FLAG = add("\uf024", "Flag", "Signs", "mark");
	public static final String FLAG_CHECKERED = add("\uf11e", "Finish flag", "Signs", "race goal");
	public static final String BOOKMARK = add("\uf02e", "Bookmark", "Signs", "save mark");
	public static final String TAG = add("\uf02b", "Tag", "Signs", "label");
	public static final String TROPHY = add("\uf091", "Trophy", "Signs", "win achievement");
	public static final String GIFT = add("\uf06b", "Gift", "Signs", "present");
	public static final String CERTIFICATE = add("\uf0a3", "Badge", "Signs", "seal");
	public static final String COMMENT = add("\uf075", "Comment", "Signs", "chat message");
	public static final String COMMENTS = add("\uf086", "Comments", "Signs", "chat");
	public static final String ENVELOPE = add("\uf0e0", "Envelope", "Signs", "mail message");
	public static final String BULLHORN = add("\uf0a1", "Bullhorn", "Signs", "announce spam");
	public static final String MICROPHONE = add("\uf130", "Microphone", "Signs", "voice");
	public static final String VOLUME = add("\uf028", "Volume", "Signs", "sound audio");
	public static final String MUSIC = add("\uf001", "Music", "Signs", "sound note");
	public static final String HEADPHONES = add("\uf025", "Headphones", "Signs", "sound listen");
	public static final String SIGNAL = add("\uf012", "Signal", "Signs", "ping connection");
	public static final String WIFI = add("\uf1eb", "Wireless", "Signs", "network");
	public static final String RSS = add("\uf09e", "Feed", "Signs", "broadcast");
	public static final String BAR_CHART = add("\uf080", "Bar chart", "Signs", "stats");
	public static final String LINE_CHART = add("\uf201", "Line chart", "Signs", "graph stats");
	public static final String PIE_CHART = add("\uf200", "Pie chart", "Signs", "stats");
	public static final String BATTERY = add("\uf240", "Battery", "Signs", "power energy");
	public static final String CHECK = add("\uf00c", "Check", "Signs", "ok yes done");
	public static final String TIMES = add("\uf00d", "Cross", "Signs", "close no");
	public static final String PLUS = add("\uf067", "Plus", "Signs", "add new");
	public static final String MINUS = add("\uf068", "Minus", "Signs", "remove");
	public static final String WARNING = add("\uf071", "Warning", "Signs", "danger alert");
	public static final String INFO = add("\uf05a", "Info", "Signs", "information");
	public static final String QUESTION = add("\uf059", "Question", "Signs", "help");
	public static final String EXCLAMATION = add("\uf06a", "Exclamation", "Signs", "important");
	public static final String PLAY = add("\uf04b", "Play", "Signs", "start run");
	public static final String PAUSE = add("\uf04c", "Pause", "Signs", "stop");
	public static final String STOP = add("\uf04d", "Stop", "Signs", "halt");
	public static final String FORWARD = add("\uf04e", "Forward", "Signs", "skip");
	public static final String HASHTAG = add("\uf292", "Hashtag", "Signs", "tag number");
	public static final String AT = add("\uf1fa", "At", "Signs", "mention");
	public static final String ASTERISK = add("\uf069", "Asterisk", "Signs", "star wildcard");
	public static final String CIRCLE = add("\uf111", "Circle", "Signs", "dot");
	public static final String SQUARE = add("\uf0c8", "Square", "Signs", "box");
	public static final String LIFE_RING = add("\uf1cd", "Life ring", "Signs", "help save");
	public static final String SPINNER = add("\uf110", "Spinner", "Signs", "loading");
	public static final String MONEY = add("\uf0d6", "Money", "Signs", "cash pay");
	public static final String GRADUATION_CAP = add("\uf19d", "Cap", "Signs", "learn tutorial");
	public static final String BOOK = add("\uf02d", "Book", "Signs", "read guide enchant");
	public static final String LANGUAGE = add("\uf1ab", "Language", "Signs", "translate");

	private Icons() {
	}

	private static String add(String glyph, String name, String section, String keywords) {
		ALL.add(new Icon(glyph, name, section, keywords));
		return glyph;
	}

	/** Every icon, by section. */
	public static List<Icon> all() {
		return List.copyOf(ALL);
	}

	/** The icons matching {@code query} ({@link Icon#matches}); all of them for a blank one. */
	public static List<Icon> search(String query) {
		if (query == null || query.isBlank()) return all();
		return ALL.stream().filter(i -> i.matches(query)).toList();
	}

	/** The icon with this glyph, if it's one of these. */
	public static Icon of(String glyph) {
		for (Icon i : ALL) if (i.glyph().equals(glyph)) return i;
		return null;
	}
}
