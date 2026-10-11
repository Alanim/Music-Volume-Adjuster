package com.musicvolumeadjuster;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

/**
 * Holds the per-track volume multiplier table, one set per {@link Profile}.
 *
 * The full track list comes from the bundled tracks.txt resource (every song in the game).
 * Every track defaults to a multiplier of 1.0 (unchanged); only tracks the person has actually
 * adjusted are stored, as a small JSON map per profile, via ConfigManager.
 *
 * The read-only {@link Profile#SUGGESTED} profile is the exception: its values come from the
 * bundled suggested.json resource and are never written to the config.
 */
@Slf4j
@Singleton
class TrackVolumes
{
	static final String CONFIG_GROUP = "musicvolumeadjuster";

	/** Config group used before the plugin was renamed; read once to migrate saved settings. */
	private static final String LEGACY_CONFIG_GROUP = "musicvolumeequalizer";
	private static final String PROFILE_KEY = "profile";
	private static final String RESET_KEY = "resetToDefault";
	private static final String DATA_KEY_PREFIX = "trackMultipliers_"; // + Profile.name()
	private static final String TRACK_LIST_RESOURCE = "/tracks.txt";
	private static final String SUGGESTED_RESOURCE = "/suggested.json";
	private static final String DISCOVERED_KEY = "discoveredTracks";

	/** Longest track name accepted as a newly discovered song; anything longer isn't a song title. */
	private static final int MAX_DISCOVERED_NAME_LENGTH = 64;

	static final double MIN_MULTIPLIER = 0.00;
	static final double MAX_MULTIPLIER = 5.00;

	private final ConfigManager configManager;
	private final Gson gson;

	/**
	 * Every known track, original display casing, sorted alphabetically: the bundled list plus any
	 * songs discovered while playing. Guarded by "this", since discoveries arrive on the client thread.
	 */
	private final List<String> allTrackNames;

	/** Normalized names of everything in allTrackNames, for quick "do we know this song" checks. */
	private final Set<String> knownNormalizedNames = new HashSet<>();

	/** Normalized names from the bundled tracks.txt only, so discovered songs can be told apart. */
	private final Set<String> bundledNormalizedNames = new HashSet<>();

	/** One "normalized track name -> multiplier" map per profile. Only non-1.0 entries are kept. */
	private final Map<Profile, Map<String, Double>> profileOverrides = new EnumMap<>(Profile.class);

	private Profile currentProfile;

	@Inject
	TrackVolumes(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
		this.allTrackNames = loadTrackNames();
		for (String name : allTrackNames)
		{
			knownNormalizedNames.add(normalize(name));
		}
		bundledNormalizedNames.addAll(knownNormalizedNames);

		migrateLegacyConfig();
		loadDiscoveredTracks();

		for (Profile profile : Profile.values())
		{
			profileOverrides.put(profile, profile.isReadOnly() ? loadSuggested() : loadOverrides(profile));
		}

		currentProfile = loadCurrentProfile();
	}

	/**
	 * The config group was renamed along with the plugin. Moves anything saved under the old group
	 * to the new one so nobody's profiles or volumes are lost, then clears the old keys. A value
	 * already present under the new group is never overwritten.
	 */
	private void migrateLegacyConfig()
	{
		List<String> keys = new ArrayList<>();
		keys.add(PROFILE_KEY);
		keys.add(RESET_KEY);
		for (Profile profile : Profile.values())
		{
			keys.add(DATA_KEY_PREFIX + profile.name());
		}

		for (String key : keys)
		{
			String legacy = configManager.getConfiguration(LEGACY_CONFIG_GROUP, key);
			if (legacy == null)
			{
				continue;
			}
			if (!RESET_KEY.equals(key) && configManager.getConfiguration(CONFIG_GROUP, key) == null)
			{
				configManager.setConfiguration(CONFIG_GROUP, key, legacy);
				log.debug("Migrated legacy config key {}", key);
			}
			configManager.unsetConfiguration(LEGACY_CONFIG_GROUP, key);
		}
	}

	/** Loads the bundled Suggested Settings table. Entries at 1.0 are dropped, like stored ones. */
	private Map<String, Double> loadSuggested()
	{
		Map<String, Double> map = new HashMap<>();
		try (InputStream is = TrackVolumes.class.getResourceAsStream(SUGGESTED_RESOURCE))
		{
			if (is == null)
			{
				log.warn("suggested.json resource not found");
				return map;
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)))
			{
				Type type = new TypeToken<Map<String, Double>>()
				{
				}.getType();
				Map<String, Double> bundled = gson.fromJson(reader, type);
				if (bundled != null)
				{
					for (Map.Entry<String, Double> entry : bundled.entrySet())
					{
						double value = clamp(entry.getValue());
						if (Math.abs(value - 1.0) >= 0.001)
						{
							map.put(normalize(entry.getKey()), value);
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to read suggested.json", e);
		}
		return map;
	}

	private List<String> loadTrackNames()
	{
		List<String> names = new ArrayList<>();
		try (InputStream is = TrackVolumes.class.getResourceAsStream(TRACK_LIST_RESOURCE))
		{
			if (is == null)
			{
				log.warn("tracks.txt resource not found");
				return names;
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					line = line.trim();
					if (!line.isEmpty())
					{
						names.add(line);
					}
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to read tracks.txt", e);
		}
		names.sort(TRACK_ORDER);
		return names;
	}

	/** Songs found in-game that the bundled list didn't have, saved so they stay listed next launch. */
	private void loadDiscoveredTracks()
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, DISCOVERED_KEY);
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			Type type = new TypeToken<List<String>>()
			{
			}.getType();
			List<String> saved = gson.fromJson(json, type);
			if (saved != null)
			{
				for (String name : saved)
				{
					insertTrack(name);
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to parse saved discovered tracks, ignoring them", e);
		}
	}

	/** Inserts a track at its alphabetical position unless it's already known. */
	private synchronized boolean insertTrack(String displayName)
	{
		if (!knownNormalizedNames.add(normalize(displayName)))
		{
			return false;
		}
		int index = 0;
		while (index < allTrackNames.size() && TRACK_ORDER.compare(allTrackNames.get(index), displayName) <= 0)
		{
			index++;
		}
		allTrackNames.add(index, displayName);
		return true;
	}

	/**
	 * Records a song the game is playing that isn't in the track list yet, so it gets its own row
	 * in the panel. It starts at 1.00 (no override stored), same as any untouched track, until the
	 * person adjusts it.
	 *
	 * @return true if the song was new (so the caller should add a row for it)
	 */
	boolean addDiscoveredTrack(String displayName)
	{
		if (displayName == null)
		{
			return false;
		}
		displayName = displayName.trim();
		if (displayName.isEmpty() || displayName.length() > MAX_DISCOVERED_NAME_LENGTH
			|| !insertTrack(displayName))
		{
			return false;
		}

		log.debug("Discovered new track '{}'", displayName);
		persistDiscovered();
		return true;
	}

	private synchronized void persistDiscovered()
	{
		List<String> discovered = new ArrayList<>();
		for (String name : allTrackNames)
		{
			if (!bundledNormalizedNames.contains(normalize(name)))
			{
				discovered.add(name);
			}
		}
		configManager.setConfiguration(CONFIG_GROUP, DISCOVERED_KEY, gson.toJson(discovered));
	}

	// Same articles the game itself ignores when alphabetizing the music list
	private static final List<String> LEADING_ARTICLES = Arrays.asList("the ", "an ", "a ");

	/** Alphabetical order, ignoring a leading "The"/"A"/"An" like the game's own music list. */
	static final Comparator<String> TRACK_ORDER = Comparator.comparing(TrackVolumes::sortKey);

	private static String sortKey(String name)
	{
		String lower = name.toLowerCase(Locale.ROOT);
		for (String article : LEADING_ARTICLES)
		{
			if (lower.startsWith(article))
			{
				return lower.substring(article.length());
			}
		}
		return lower;
	}

	private Profile loadCurrentProfile()
	{
		String saved = configManager.getConfiguration(CONFIG_GROUP, PROFILE_KEY);
		if (saved != null)
		{
			try
			{
				return Profile.valueOf(saved);
			}
			catch (IllegalArgumentException e)
			{
				log.warn("Unknown saved profile '{}', defaulting to USER_1", saved);
			}
		}
		return Profile.USER_1;
	}

	private Map<String, Double> loadOverrides(Profile profile)
	{
		Map<String, Double> map = new HashMap<>();
		String json = configManager.getConfiguration(CONFIG_GROUP, DATA_KEY_PREFIX + profile.name());
		if (json == null || json.isEmpty())
		{
			return map;
		}
		try
		{
			Type type = new TypeToken<Map<String, Double>>()
			{
			}.getType();
			Map<String, Double> saved = gson.fromJson(json, type);
			if (saved != null)
			{
				map.putAll(saved);
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to parse saved multipliers for {}, starting fresh", profile, e);
		}
		return map;
	}

	private void persist(Profile profile)
	{
		if (profile.isReadOnly())
		{
			return;
		}
		configManager.setConfiguration(CONFIG_GROUP, DATA_KEY_PREFIX + profile.name(), gson.toJson(profileOverrides.get(profile)));
	}

	/** Lowercases and strips colour tags so lookups are forgiving. */
	static String normalize(String rawName)
	{
		if (rawName == null)
		{
			return "";
		}
		return Text.removeTags(rawName).trim().toLowerCase(Locale.ROOT);
	}

	/** Strips colour tags but keeps the original casing, for display purposes. */
	static String cleanDisplayName(String rawName)
	{
		if (rawName == null)
		{
			return "";
		}
		return Text.removeTags(rawName).trim();
	}

	/** @param normalizedName a name already passed through {@link #normalize} */
	double getMultiplier(String normalizedName)
	{
		return profileOverrides.get(currentProfile).getOrDefault(normalizedName, 1.0);
	}

	/**
	 * Sets a track's multiplier in the active profile and persists it. A value of 1.0 clears the
	 * override instead of storing it, so untouched tracks don't bloat the saved config.
	 *
	 * @param displayName the track's un-normalized name, as shown in the panel
	 */
	void setMultiplier(String displayName, double value)
	{
		if (currentProfile.isReadOnly())
		{
			return;
		}
		value = clamp(value);
		String norm = normalize(displayName);
		Map<String, Double> map = profileOverrides.get(currentProfile);
		if (Math.abs(value - 1.0) < 0.001)
		{
			map.remove(norm);
		}
		else
		{
			map.put(norm, value);
		}
		persist(currentProfile);
	}

	static double clamp(double value)
	{
		value = Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, value));
		// snap to the nearest 0.01 to avoid float drift
		return Math.round(value * 100.0) / 100.0;
	}

	/** Every known track, alphabetical, in its original display casing. A snapshot copy. */
	synchronized List<String> getAllTrackNames()
	{
		return new ArrayList<>(allTrackNames);
	}

	Profile getCurrentProfile()
	{
		return currentProfile;
	}

	/** Switches the active profile. Does not touch any stored data. */
	void setCurrentProfile(Profile profile)
	{
		currentProfile = profile == null ? Profile.USER_1 : profile;
		configManager.setConfiguration(CONFIG_GROUP, PROFILE_KEY, currentProfile.name());
	}

	/** Clears every override in the given profile back to 1.0 (no stored entries). No-op if read-only. */
	void resetProfile(Profile profile)
	{
		if (profile.isReadOnly())
		{
			return;
		}
		profileOverrides.put(profile, new HashMap<>());
		persist(profile);
	}

	/**
	 * Replaces every adjustment in {@code to} with a copy of those in {@code from}, so the copy can
	 * be edited freely without touching the source (e.g. the read-only Suggested Settings).
	 *
	 * @return false if nothing was copied (the target is read-only, or source and target are the same)
	 */
	boolean copyProfile(Profile from, Profile to)
	{
		if (to.isReadOnly() || from == to)
		{
			return false;
		}
		profileOverrides.put(to, new HashMap<>(profileOverrides.get(from)));
		persist(to);
		return true;
	}
}
