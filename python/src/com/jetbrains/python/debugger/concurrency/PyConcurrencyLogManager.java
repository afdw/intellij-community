
/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.debugger.concurrency;

import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.jetbrains.python.debugger.PyConcurrencyEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public abstract class PyConcurrencyLogManager {
  private List<PyConcurrencyEvent> myLog;
  private final Object myLogObject = new Object();
  private List<LogListener> myListeners = new ArrayList<LogListener>();
  private XDebugSession lastSession;
  protected Project myProject;
  protected long pauseTime;

  public PyConcurrencyLogManager(Project project) {
    myProject = project;
    myLog = new ArrayList<PyConcurrencyEvent>();
  }

  public Integer getSize() {
    return myLog.size();
  }

  public PyConcurrencyEvent getEventAt(int index) {
    return myLog.get(index);
  }

  public abstract HashMap getStatistics();

  public long getPauseTime() {
    return pauseTime;
  }

  public void setPauseTime(long pauseTime) {
    this.pauseTime = pauseTime;
  }

  public String getStringRepresentation() {
    StringBuilder resultBuilder = new StringBuilder();
    resultBuilder.append("<html>Size: ").append(myLog.size()).append("<br>");
    for (PyConcurrencyEvent event: myLog) {
      resultBuilder.append(event.toString());
    }
    resultBuilder.append("</html>");
    return resultBuilder.toString();
  }

  public void recordEvent(@NotNull XDebugSession debugSession, PyConcurrencyEvent event) {
    synchronized (myLogObject) {
      if (((lastSession == null) || (debugSession != lastSession)) && event == null) {
        lastSession = debugSession;
        myLog = new ArrayList<PyConcurrencyEvent>();
        addSessionListener();
        notifyListeners(true);
        return;
      }
      myLog.add(event);
      notifyListeners(false);
    }
  }

  public void addSessionListener() {
    lastSession.addSessionListener(new XDebugSessionListener() {
      @Override
      public void sessionPaused() {
        setPauseTime(System.currentTimeMillis());
        notifyListeners(false);
      }

      @Override
      public void sessionResumed() {
        setPauseTime(0);
      }

      @Override
      public void sessionStopped() {
        notifyListeners(false);
      }

      @Override
      public void stackFrameChanged() {

      }

      @Override
      public void beforeSessionResume() {

      }
    });
  }

  public interface LogListener {
    void logChanged(boolean isNewSession);
  }

  public void registerListener(@NotNull LogListener logListener) {
    myListeners.add(logListener);
  }

  public void notifyListeners(boolean isNewSession) {
    for (LogListener logListener : myListeners) {
      logListener.logChanged(isNewSession);
    }
  }

}
