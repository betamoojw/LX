/**
 * Copyright 2013- Mark C. Slee, Heron Arts LLC
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

package heronarts.lx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import heronarts.lx.modulator.LXModulator;

public abstract class LXModulatorComponent extends LXComponent implements LXLoopTask {

  private final List<LXModulator> mutableModulators = new ArrayList<LXModulator>();

  public final List<LXModulator> modulators = Collections.unmodifiableList(this.mutableModulators);

  public class Timer {
    public long loopNanos;
  }

  protected Timer constructTimer() {
    return new Timer();
  }

  public final Timer timer = constructTimer();

  protected LXModulatorComponent(LX lx) {
    this(lx, null);
  }

  protected LXModulatorComponent(LX lx, String label) {
    super(lx, label);
    addArray("modulator", this.modulators);
  }

  private void _addModulator(LXModulator modulator, int index) {
    if (modulator == null) {
      throw new IllegalArgumentException("Cannot add null modulator");
    }
    if (index > this.mutableModulators.size()) {
      throw new IllegalArgumentException("Invalid modulator index: " + index);
    }
    if (index < 0) {
      index = this.mutableModulators.size();
    }
    checkForReentrancy(modulator, "add");
    if (this.mutableModulators.contains(modulator)) {
      throw new IllegalStateException("Cannot add modulator twice: " + modulator);
    }
    modulator.setIndex(index);
    this.mutableModulators.add(index, modulator);
    _reindexModulators();
  }

  private void _reindexModulators() {
    int i = 0;
    for (LXModulator modulator : this.modulators) {
      modulator.setIndex(i++);
    }
  }

  public LXModulator addModulator(String path, LXModulator modulator) {
    _addModulator(modulator, -1);
    addChild(path, modulator);
    return modulator;
  }

  public final LXModulator addModulator(LXModulator modulator) {
    return addModulator(modulator, -1);
  }

  public LXModulator addModulator(LXModulator modulator, int index) {
    _addModulator(modulator, index);
    modulator.setParent(this);
    return modulator;
  }

  public LXModulator moveModulator(LXModulator modulator, int index) {
    if (!this.modulators.contains(modulator)) {
      throw new IllegalArgumentException("Cannot move modulator not in component: " + modulator);
    }
    this.mutableModulators.remove(modulator);
    this.mutableModulators.add(index, modulator);
    _reindexModulators();
    return modulator;
  }

  public final LXModulator startModulator(LXModulator modulator) {
    addModulator(modulator).start();
    return modulator;
  }

  public LXModulator removeModulator(LXModulator modulator) {
    checkForReentrancy(modulator, "remove");
    this.mutableModulators.remove(modulator);
    modulator.dispose();
    _reindexModulators();
    return modulator;
  }

  public LXModulator getModulator(String label) {
    for (LXModulator modulator : this.modulators) {
      if (modulator.getLabel().equals(label)) {
        return modulator;
      }
    }
    return null;
  }

  public List<LXModulator> getModulators() {
    return this.modulators;
  }

  @Override
  public void dispose() {
    checkForReentrancy(null, "dispose");
    for (LXModulator modulator : this.mutableModulators) {
      modulator.dispose();
    }
    this.mutableModulators.clear();
    super.dispose();
  }

  private void checkForReentrancy(LXModulator target, String operation) {
    if (this.loopingModulator != null) {
      throw new IllegalStateException(
        "LXModulatorComponent may not modify modulators while looping," +
        " component: " + toString() +
        " looping: " + this.loopingModulator.toString(this) +
        " " + operation + ": " + (target != null ? target.toString() : "null")
      );
    }
  }

  private LXModulator loopingModulator = null;

  @Override
  public void loop(double deltaMs) {
    for (LXModulator modulator : this.mutableModulators) {
      this.loopingModulator = modulator;
      modulator.loop(deltaMs);
    }
    this.loopingModulator = null;
  }

}
