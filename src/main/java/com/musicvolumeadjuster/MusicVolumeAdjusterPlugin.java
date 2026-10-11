package com.musicvolumeadjuster;

import java.util.List;
import javax.inject.Inject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MidiRequest;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Music Volume Adjuster",
	description = "Applies a per-track volume adjustment so loud and quiet songs sound even",
	tags = {"music", "volume", "audio", "equalizer", "adjuster"}
)
public class MusicVolumeAdjusterPlugin extends Plugin
{
	private static final int MAX_VOLUME = 255;

	/** A new song starts at this volume and ramps up to its target. 0 would stop playback. */
	private static final int RAMP_START_VOLUME = 1;
	private static final long RAMP_DURATION_MS = 2000;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private TrackVolumes trackVolumes;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private MusicVolumeAdjusterConfig config;

	@Provides
	MusicVolumeAdjusterConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MusicVolumeAdjusterConfig.class);
	}

	private NavigationButton navButton;
	private MusicVolumeAdjusterPanel panel;

	private String currentTrack = "";

	/** When the current volume ramp began, or -1 if no ramp is running. */
	private long rampStartMs = -1;

	/** The volume the running ramp is heading for. Only meaningful while rampStartMs >= 0. */
	private int rampTarget = -1;

	/**
	 * The player's own volume slider value. Note: client.getMusicVolume() always returns this
	 * value and never reflects what setMusicVolume() last set, so it's a reliable way to detect
	 * a genuine slider move, but useless for detecting whether our own adjustment is still live.
	 */
	private int baseVolume = -1;

	/** The last value we passed to setMusicVolume (either baseVolume or an adjusted value). */
	private int appliedVolume = -1;

	/**
	 * True while we own the live volume and keep re-asserting appliedVolume: either an adjusted
	 * volume (different from baseVolume) is in effect, or a ramp is running.
	 */
	private boolean adjustmentActive = false;

	/**
	 * True while a jingle (e.g. quest complete, diary complete, slayer task complete) is one of
	 * the active midi requests. Jingles share the music volume channel but aren't reflected in
	 * the Music tab's "now playing" text, so without this they'd inherit whatever multiplier the
	 * background track has, which can make them uncomfortably loud on a boosted track.
	 */
	private boolean jinglePlaying = false;

	@Override
	protected void startUp()
	{
		panel = new MusicVolumeAdjusterPanel(trackVolumes, this);
		navButton = NavigationButton.builder()
			.tooltip("Music Volume Adjuster")
			.icon(ImageUtil.loadImageResource(getClass(), "/icon.png"))
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		resetState();
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}

		clientThread.invoke(() ->
		{
			restoreBaseVolume();
			resetState();
		});

		panel = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Logged out: put the player's own volume back so it isn't saved as the adjusted one
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			restoreBaseVolume();
			resetState();
		}
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		restoreBaseVolume();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!TrackVolumes.CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("profile".equals(event.getKey()))
		{
			Profile newProfile;
			try
			{
				newProfile = Profile.valueOf(event.getNewValue());
			}
			catch (Exception e)
			{
				newProfile = Profile.USER_1;
			}
			trackVolumes.setCurrentProfile(newProfile);
			if (panel != null)
			{
				panel.refreshAllMultipliers();
			}
			reapplyCurrentTrackLive();
		}
		else if ("resetToDefault".equals(event.getKey()))
		{
			// The checkbox-as-button pattern: only act on the true edge (the person confirmed
			// the warning dialog), then flip it back off so it reads as a momentary action.
			if (!"true".equals(event.getNewValue()))
			{
				return;
			}

			Profile profile = trackVolumes.getCurrentProfile();
			if (profile.isReadOnly())
			{
				log.debug("Ignoring reset of read-only profile {}", profile);
			}
			else
			{
				trackVolumes.resetProfile(profile);
				log.debug("Reset profile {} to default", profile);

				if (panel != null)
				{
					panel.refreshAllMultipliers();
				}
				reapplyCurrentTrackLive();
			}

			configManager.setConfiguration(TrackVolumes.CONFIG_GROUP, "resetToDefault", "false");
		}
		else if ("importSuggested".equals(event.getKey()))
		{
			if (!"true".equals(event.getNewValue()))
			{
				return;
			}

			importSuggested();
			configManager.setConfiguration(TrackVolumes.CONFIG_GROUP, "importSuggested", "false");
		}
		else if ("copyNow".equals(event.getKey()))
		{
			if (!"true".equals(event.getNewValue()))
			{
				return;
			}

			copyToTarget();
			configManager.setConfiguration(TrackVolumes.CONFIG_GROUP, "copyNow", "false");
		}
	}

	/** Overwrites the active user profile with the Suggested Settings. */
	private void importSuggested()
	{
		Profile target = trackVolumes.getCurrentProfile();
		if (target.isReadOnly())
		{
			showStatus("Switch to a User profile to import into it");
			return;
		}

		if (trackVolumes.copyProfile(Profile.SUGGESTED, target))
		{
			log.debug("Imported suggested volumes into {}", target);
			profileContentsChanged("Imported suggested volumes");
		}
	}

	/** Overwrites the profile chosen in the settings with a copy of the active profile. */
	private void copyToTarget()
	{
		Profile source = trackVolumes.getCurrentProfile();
		Profile target = config.copyTarget().toProfile();
		if (source == target)
		{
			showStatus("Pick a different profile to copy to");
			return;
		}

		if (trackVolumes.copyProfile(source, target))
		{
			log.debug("Copied {} to {}", source, target);
			showStatus("Copied to " + target);
		}
	}

	/** The active profile's volumes were replaced: refresh what's shown and what's playing. */
	private void profileContentsChanged(String status)
	{
		if (panel != null)
		{
			panel.refreshAllMultipliers();
		}
		showStatus(status);
		reapplyCurrentTrackLive();
	}

	private void showStatus(String text)
	{
		if (panel != null)
		{
			panel.showStatus(text);
		}
	}

	/**
	 * Recalculates the live volume for whatever's currently playing, e.g. after a profile switch or
	 * after a profile's contents were replaced by a copy/import. Applied straight away, not ramped.
	 */
	void reapplyCurrentTrackLive()
	{
		clientThread.invoke(() ->
		{
			if (baseVolume < 0 || client.getGameState() != GameState.LOGGED_IN
				|| currentTrack.isEmpty() || jinglePlaying)
			{
				return;
			}
			applyModifier();
		});
	}

	/**
	 * The game silently resets the live volume back to the slider value at times (e.g. switching
	 * inventory tabs), inside a client script, and getMusicVolume() can't tell us when — it always
	 * mirrors the slider anyway. So rather than trying to detect a reset, this just re-asserts the
	 * adjusted volume after every script while one should be active. That's a lot of calls, but
	 * each is a cheap, idempotent int write, and correcting within the same frame the game resets
	 * it is what keeps a tab switch from producing an audible dip. Not logged per-call, since at
	 * this frequency that's log noise rather than useful signal.
	 */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (adjustmentActive)
		{
			client.setMusicVolume(appliedVolume);
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		// getMusicVolume() always mirrors the player's own slider, never what we set, so any
		// change here is a genuine slider move (or the first reading after login)
		int reading = client.getMusicVolume();
		if (reading != baseVolume)
		{
			setBase(reading);
		}

		// Duck to the player's own (unboosted/uncut) volume while a jingle is playing, since
		// jingles share the music channel but don't show up in the "now playing" text, so we'd
		// otherwise apply whatever multiplier the background track has to them too.
		boolean jingleNow = isJinglePlaying();
		if (jingleNow != jinglePlaying)
		{
			jinglePlaying = jingleNow;
			if (jinglePlaying)
			{
				log.debug("Jingle started, holding at base {}", baseVolume);
				holdAtBase();
			}
			else
			{
				log.debug("Jingle ended, resuming track adjustment");
				applyModifier();
			}
		}

		// Detect song transitions from the Music tab's "Now playing" text
		Widget trackWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TEXT);
		if (trackWidget != null && baseVolume >= 0)
		{
			String rawText = trackWidget.getText();
			String track = TrackVolumes.normalize(rawText);
			if (!track.isEmpty() && !track.equals(currentTrack))
			{
				currentTrack = track;
				log.debug("Track changed: '{}'", track);

				// If a jingle is playing right now, hold at the player's own volume and leave it
				// to the jingle-ended branch above to apply the new song's volume, rather than
				// ramping up underneath the jingle. Otherwise slam to the floor and ramp up.
				if (jinglePlaying)
				{
					holdAtBase();
				}
				else
				{
					startRamp();
				}

				if (panel != null)
				{
					String displayName = TrackVolumes.cleanDisplayName(rawText);

					// A song the track list doesn't have yet (e.g. added to the game since): give
					// it a row of its own. It plays at 1.00 until the person adjusts it.
					if (trackVolumes.addDiscoveredTrack(displayName))
					{
						panel.addTrack(displayName);
					}
					panel.setNowPlayingTrack(displayName);
				}
			}
		}

		// The game silently resets the live volume back to the slider value at times (e.g.
		// switching inventory tabs), and getMusicVolume() can't tell us when that happens, since
		// it always reads the slider anyway. So instead of trying to detect the reset, just keep
		// re-asserting the adjusted volume every tick while one should be active. Cheap (one call
		// per tick, only while a track is actually boosted, cut or ramping) and needs no detection
		// at all. While a ramp is running, the same call also moves the volume one step along it.
		if (rampStartMs >= 0)
		{
			advanceRamp();
		}
		else if (adjustmentActive)
		{
			client.setMusicVolume(appliedVolume);
		}
	}

	/**
	 * Called from the panel (on the EDT) when the player changes a track's multiplier — either
	 * from the full list or from the pinned Now Playing row. If that track is playing right now,
	 * the volume is recalculated from the base volume immediately, instead of waiting for the
	 * next song change.
	 */
	void onMultiplierChanged(String trackDisplayName)
	{
		clientThread.invoke(() ->
		{
			if (baseVolume < 0 || client.getGameState() != GameState.LOGGED_IN || jinglePlaying)
			{
				return;
			}
			String norm = TrackVolumes.normalize(trackDisplayName);
			if (norm.equals(currentTrack))
			{
				applyModifier();
			}
		});
	}

	/** True if any currently active midi request is a jingle (a short sting like quest/diary/slayer-task complete). */
	private boolean isJinglePlaying()
	{
		List<MidiRequest> active = client.getActiveMidiRequests();
		if (active == null)
		{
			return false;
		}
		for (MidiRequest request : active)
		{
			if (request != null && request.isJingle())
			{
				return true;
			}
		}
		return false;
	}

	private void setBase(int volume)
	{
		log.debug("Base volume {} -> {}", baseVolume, volume);
		baseVolume = volume;
		appliedVolume = volume;
		adjustmentActive = false;
		rampStartMs = -1;
	}

	/** The volume the current track should settle at: the player's volume times the track's multiplier. */
	private int computeTarget()
	{
		double multiplier = trackVolumes.getMultiplier(currentTrack);
		int target = (int) Math.round(baseVolume * multiplier);
		target = Math.max(0, Math.min(MAX_VOLUME, target));

		log.debug("'{}': base {} x {} -> {}", currentTrack, baseVolume, multiplier, target);
		return target;
	}

	private void setLive(int volume)
	{
		client.setMusicVolume(volume);
		appliedVolume = volume;
	}

	/** Puts the live volume at the player's own level and stops adjusting until told otherwise. */
	private void holdAtBase()
	{
		rampStartMs = -1;
		adjustmentActive = false;
		if (baseVolume >= 0)
		{
			setLive(baseVolume);
		}
	}

	/**
	 * Starts a new song: slams the volume to the floor, then {@link #advanceRamp} raises it to the
	 * song's target over {@link #RAMP_DURATION_MS}. If the target is already at or below the floor
	 * (e.g. the player has music muted) there's nothing to ramp, so it's set directly instead.
	 */
	private void startRamp()
	{
		if (baseVolume < 0)
		{
			return;
		}

		int target = computeTarget();
		if (target <= RAMP_START_VOLUME)
		{
			rampStartMs = -1;
			setLive(target);
			adjustmentActive = target != baseVolume;
			return;
		}

		rampTarget = target;
		rampStartMs = System.currentTimeMillis();
		adjustmentActive = true;
		setLive(RAMP_START_VOLUME);
	}

	/** Moves the live volume one step along the running ramp, finishing it once the time is up. */
	private void advanceRamp()
	{
		long elapsed = System.currentTimeMillis() - rampStartMs;
		if (elapsed >= RAMP_DURATION_MS)
		{
			rampStartMs = -1;
			setLive(rampTarget);
			adjustmentActive = rampTarget != baseVolume;
			return;
		}

		double progress = elapsed / (double) RAMP_DURATION_MS;
		setLive(RAMP_START_VOLUME + (int) Math.round((rampTarget - RAMP_START_VOLUME) * progress));
	}

	/**
	 * Applies the current track's volume straight away, with no ramp. Used when something changes
	 * mid-song (a multiplier edit, a profile switch, a jingle ending). If a ramp is already running
	 * it just gets a new destination instead, so the volume keeps rising smoothly.
	 */
	private void applyModifier()
	{
		if (baseVolume < 0)
		{
			return;
		}

		int target = computeTarget();
		if (rampStartMs >= 0 && target > RAMP_START_VOLUME)
		{
			rampTarget = target;
			return;
		}

		rampStartMs = -1;
		setLive(target);
		adjustmentActive = target != baseVolume;
	}

	/** Puts the live volume back to the player's own level if we had adjusted it. */
	private void restoreBaseVolume()
	{
		if (baseVolume >= 0 && adjustmentActive)
		{
			client.setMusicVolume(baseVolume);
			appliedVolume = baseVolume;
		}
		adjustmentActive = false;
		rampStartMs = -1;
	}

	private void resetState()
	{
		boolean hadTrack = !currentTrack.isEmpty();
		currentTrack = "";
		rampStartMs = -1;
		rampTarget = -1;
		baseVolume = -1;
		appliedVolume = -1;
		adjustmentActive = false;
		jinglePlaying = false;

		if (hadTrack && panel != null)
		{
			panel.setNowPlayingTrack("");
		}
	}
}
