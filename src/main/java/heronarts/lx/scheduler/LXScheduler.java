/**
 * Copyright 2020- Mark C. Slee, Heron Arts LLC
 *
 * This file is part of the LX Studio software library. By using
 * LX, you agree to the terms of the LX Studio Software License
 * and Distribution Agreement, available at: http://lx.studio/license
 *
 * Please note that the LX license is not open-source. The license
 * allows for free, non-commercial use.
 *
 * HERON ARTS MAKES NO WARRANTY, EXPRESS, IMPLIED, STATUTORY, OR
 * OTHERWISE, AND SPECIFICALLY DISCLAIMS ANY WARRANTY OF
 * MERCHANTABILITY, NON-INFRINGEMENT, OR FITNESS FOR A PARTICULAR
 * PURPOSE, WITH RESPECT TO THE SOFTWARE.
 *
 * @author Mark C. Slee <mark@heronarts.com>
 */

package heronarts.lx.scheduler;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;

import heronarts.lx.LX;
import heronarts.lx.LXComponent;
import heronarts.lx.LXLoopTask;
import heronarts.lx.LXSerializable;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.BoundedParameter;

public class LXScheduler extends LXComponent implements LXLoopTask {

  public interface Listener {

    enum Change {
      TRY,
      NEW,
      SAVE,
      OPEN
    };

    public void scheduleChanged(File file, Change change);

    public void entryAdded(LXScheduler scheduler, LXScheduledProject entry);
    public void entryRemoved(LXScheduler scheduler, LXScheduledProject entry);
    public void entryMoved(LXScheduler scheduler, LXScheduledProject entry);
  }

  private final List<Listener> listeners = new ArrayList<Listener>();

  private File file;

  private final List<LXScheduledProject> mutableEntries = new ArrayList<LXScheduledProject>();

  public final List<LXScheduledProject> entries = Collections.unmodifiableList(this.mutableEntries);

  public final BooleanParameter enabled =
    new BooleanParameter("Enabled", false)
    .setDescription("Whether the scheduler is currently enabled");

  public final BooleanParameter fade =
    new BooleanParameter("Fade", true)
    .setDescription("Whether to fade in and out on project transitions");

  public final BoundedParameter fadeTimeSecs = (BoundedParameter)
    new BoundedParameter("Fade Time", 5, 0, 60)
    .setDescription("Fade time in seconds")
    .setUnits(BoundedParameter.Units.SECONDS);

  public LXScheduler(LX lx) {
    super(lx);
    addParameter("enabled", this.enabled);
    addParameter("fade", this.fade);
    addParameter("fadeTimeSecs", this.fadeTimeSecs);
    addArray("projects", this.entries);
  }

  public LXScheduler addListener(Listener listener) {
    Objects.requireNonNull(listener);
    if (this.listeners.contains(listener)) {
      throw new IllegalStateException("Cannot add duplicate LXScheduler.Listener: " + listener);
    }
    this.listeners.add(listener);
    return this;
  }

  public LXScheduler removeListener(Listener listener) {
    if (!this.listeners.contains(listener)) {
      throw new IllegalStateException("May not remove non-registered LXScheduler.Listener: " + listener);
    }
    this.listeners.add(listener);
    return this;
  }

  private void _reindexEntries() {
    int i = 0;
    for (LXScheduledProject entry : this.entries) {
      entry.setIndex(i++);
    }
  }

  public LXScheduledProject addEntry() {
    return addEntry(null);
  }

  public LXScheduledProject addEntry(File projectFile) {
    LXScheduledProject entry = new LXScheduledProject(this);
    this.mutableEntries.add(entry);
    _reindexEntries();
    if (projectFile != null) {
      entry.setProject(projectFile);
    }
    for (Listener listener : this.listeners) {
      listener.entryAdded(this, entry);
    }
    return entry;
  }

  public LXScheduler removeEntry(LXScheduledProject entry) {
    if (!this.mutableEntries.contains(entry)) {
      throw new IllegalStateException("Cannot remove non-existent entry: " + entry);
    }
    this.mutableEntries.remove(entry);
    _reindexEntries();
    for (Listener listener : this.listeners) {
      listener.entryRemoved(this, entry);
    }
    return this;
  }

  public LXScheduler moveEntry(LXScheduledProject entry, int index) {
    if (!this.mutableEntries.contains(entry)) {
      throw new IllegalStateException("Cannot move non-existent entry: " + entry);
    }
    this.mutableEntries.remove(entry);
    this.mutableEntries.add(index, entry);
    for (Listener listener : this.listeners) {
      listener.entryMoved(this, entry);
    }
    return this;
  }

  private final Calendar calendar = Calendar.getInstance();

  private int getTimeSecsOfDay(long millis) {
    this.calendar.setTimeInMillis(millis);
    return
      60 * 60 * this.calendar.get(Calendar.HOUR_OF_DAY) +
      60 * this.calendar.get(Calendar.MINUTE) +
      this.calendar.get(Calendar.SECOND);
  }

  @Override
  public void loop(double deltaMs) {
    if (!this.lx.preferences.schedulerEnabled.isOn()) {
      return;
    }
    if (!this.enabled.isOn()) {
      return;
    }

    long thisFrameSecsOfDay = getTimeSecsOfDay(this.lx.engine.nowMillis);
    long prevFrameSecsOfDay = getTimeSecsOfDay(this.lx.engine.nowMillis - (long) Math.ceil(deltaMs));
    for (LXScheduledProject entry : this.entries) {
      if (entry.enabled.isOn()) {
        int thresholdSecsOfDay =
          60 * 60 * entry.hours.getValuei() +
          60 * entry.minutes.getValuei() +
          entry.seconds.getValuei();

        if (thresholdSecsOfDay == 0) {
          // Special handling of midnight
          if (thisFrameSecsOfDay >= thresholdSecsOfDay && prevFrameSecsOfDay > thisFrameSecsOfDay) {
            entry.open();
          }
        } else if (prevFrameSecsOfDay < thresholdSecsOfDay && thresholdSecsOfDay <= thisFrameSecsOfDay) {
          entry.open();
        }
      }
    }
  }

  protected void setSchedule(File file, Listener.Change change) {
    this.file = file;
    for (Listener listener : this.listeners) {
      listener.scheduleChanged(file, change);
    }
    this.lx.preferences.setSchedule(file);
  }

  public void newSchedule() {
    closeSchedule();
    this.load(this.lx, new JsonObject());
    setSchedule(null, Listener.Change.NEW);
  }

  public void openSchedule(File file) {
    for (Listener listener : this.listeners) {
      listener.scheduleChanged(file, Listener.Change.TRY);
    }
    try (FileReader fr = new FileReader(file)) {
      JsonObject obj = new Gson().fromJson(fr, JsonObject.class);
      closeSchedule();
      this.lx.setScheduleLoadingFlag(true);
      this.load(this.lx, obj);
      setSchedule(file, Listener.Change.OPEN);
      LX.log("Schedule loaded successfully from " + file.toString());
    } catch (IOException iox) {
      LX.error("Could not load project file: " + iox.getLocalizedMessage());
      this.lx.pushError(iox, "Could not load schedule file: " + iox.getLocalizedMessage());
    } catch (Exception x) {
      LX.error(x, "Exception in openProject: " + x.getLocalizedMessage());
      this.lx.pushError(x, "Exception in openSchedule: " + x.getLocalizedMessage());
    }
    this.lx.setScheduleLoadingFlag(false);
  }

  private void closeSchedule() {
    // Nothing needed just now!
  }

  public void saveSchedule() {
    if (this.file != null) {
      saveSchedule(this.file);
    }
  }

  public void saveSchedule(File file) {
    if (!this.lx.getPermissions().canSave()) {
      return;
    }

    JsonObject obj = new JsonObject();
    this.save(this.lx, obj);

    try (JsonWriter writer = new JsonWriter(new FileWriter(file))) {
      writer.setIndent("  ");
      new GsonBuilder().create().toJson(obj, writer);
      LX.log("Setlist saved successfully to " + file.toString());
      setSchedule(file, Listener.Change.SAVE);
    } catch (IOException iox) {
      LX.error(iox, "Could not write schedule to output file: " + file.toString());
    }
  }

  private final static String KEY_VERSION = "version";
  private final static String KEY_TIMESTAMP = "timestamp";
  private final static String KEY_ENTRIES = "entries";

  @Override
  public void load(LX lx, JsonObject obj) {
    for (int i = this.entries.size() - 1; i >= 0; --i) {
      removeEntry(this.entries.get(i));
    }
    if (obj.has(KEY_ENTRIES)) {
      JsonArray entryArr = obj.get(KEY_ENTRIES).getAsJsonArray();
      for (JsonElement entryElem : entryArr) {
        JsonObject entryObj = entryElem.getAsJsonObject();
        addEntry().load(lx, entryObj);
      }
    }
    super.load(lx, obj);
  }

  @Override
  public void save(LX lx, JsonObject obj) {
    obj.addProperty(KEY_VERSION, LX.VERSION);
    obj.addProperty(KEY_TIMESTAMP, System.currentTimeMillis());
    obj.add(KEY_ENTRIES, LXSerializable.Utils.toArray(lx, this.entries));
    super.save(lx, obj);
  }

}
