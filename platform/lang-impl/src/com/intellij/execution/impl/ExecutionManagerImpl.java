/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.execution.impl;

import com.intellij.CommonBundle;
import com.intellij.execution.*;
import com.intellij.execution.configuration.CompatibilityAwareRunProfile;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.execution.ui.RunContentManagerImpl;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Trinity;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.docking.DockManager;
import com.intellij.util.Alarm;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Collections;
import java.util.List;

/**
 * @author dyoma
 */
public class ExecutionManagerImpl extends ExecutionManager implements ProjectComponent {
  public static final Key<Object> EXECUTION_SESSION_ID_KEY = Key.create("EXECUTION_SESSION_ID_KEY");

  private static final Logger LOG = Logger.getInstance(ExecutionManagerImpl.class);
  private static final ProcessHandler[] EMPTY_PROCESS_HANDLERS = new ProcessHandler[0];

  private final Project myProject;

  private RunContentManagerImpl myContentManager;
  private final Alarm awaitingTerminationAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);
  private final List<Trinity<RunContentDescriptor, RunnerAndConfigurationSettings, Executor>> myRunningConfigurations =
    ContainerUtil.createLockFreeCopyOnWriteList();

  /**
   * reflection
   */
  ExecutionManagerImpl(final Project project) {
    myProject = project;
  }

  @Override
  public void projectOpened() {
    ((RunContentManagerImpl)getContentManager()).init();
  }

  @Override
  public void projectClosed() {
  }

  @Override
  public void initComponent() {
  }

  @Override
  public void disposeComponent() {
    for (Trinity<RunContentDescriptor, RunnerAndConfigurationSettings, Executor> trinity : myRunningConfigurations) {
      Disposer.dispose(trinity.first);
    }
    myRunningConfigurations.clear();
  }

  @NotNull
  @Override
  public RunContentManager getContentManager() {
    if (myContentManager == null) {
      myContentManager = new RunContentManagerImpl(myProject, DockManager.getInstance(myProject));
      Disposer.register(myProject, myContentManager);
    }
    return myContentManager;
  }

  @NotNull
  @Override
  public ProcessHandler[] getRunningProcesses() {
    List<ProcessHandler> handlers = null;
    for (RunContentDescriptor descriptor : getContentManager().getAllDescriptors()) {
      ProcessHandler processHandler = descriptor.getProcessHandler();
      if (processHandler != null) {
        if (handlers == null) {
          handlers = new SmartList<ProcessHandler>();
        }
        handlers.add(processHandler);
      }
    }
    return handlers == null ? EMPTY_PROCESS_HANDLERS : handlers.toArray(new ProcessHandler[handlers.size()]);
  }

  @Override
  public void compileAndRun(@NotNull final Runnable startRunnable,
                            @NotNull final ExecutionEnvironment environment,
                            @Nullable final RunProfileState state,
                            @Nullable final Runnable onCancelRunnable) {
    long id = environment.getExecutionId();
    if (id == 0) {
      id = environment.assignNewExecutionId();
    }

    RunProfile profile = environment.getRunProfile();
    if (!(profile instanceof RunConfiguration)) {
      startRunnable.run();
      return;
    }

    final RunConfiguration runConfiguration = (RunConfiguration)profile;
    final List<BeforeRunTask> beforeRunTasks = RunManagerEx.getInstanceEx(myProject).getBeforeRunTasks(runConfiguration);
    if (beforeRunTasks.isEmpty()) {
      startRunnable.run();
    }
    else {
      DataContext context = environment.getDataContext();
      final DataContext projectContext = context != null ? context : SimpleDataContext.getProjectContext(myProject);
      final long finalId = id;
      final Long executionSessionId = new Long(id);
      ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
        /** @noinspection SSBasedInspection*/
        @Override
        public void run() {
          for (BeforeRunTask task : beforeRunTasks) {
            if (myProject.isDisposed()) {
              return;
            }
            @SuppressWarnings("unchecked")
            BeforeRunTaskProvider<BeforeRunTask> provider = BeforeRunTaskProvider.getProvider(myProject, task.getProviderId());
            if (provider == null) {
              LOG.warn("Cannot find BeforeRunTaskProvider for id='" + task.getProviderId() + "'");
              continue;
            }
            ExecutionEnvironment taskEnvironment = new ExecutionEnvironmentBuilder(environment).contentToReuse(null).build();
            taskEnvironment.setExecutionId(finalId);
            EXECUTION_SESSION_ID_KEY.set(taskEnvironment, executionSessionId);
            if (!provider.executeTask(projectContext, runConfiguration, taskEnvironment, task)) {
              if (onCancelRunnable != null) {
                SwingUtilities.invokeLater(onCancelRunnable);
              }
              return;
            }
          }
          // important! Do not use DumbService.smartInvokeLater here because it depends on modality state
          // and execution of startRunnable could be skipped if modality state check fails
          SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
              if (!myProject.isDisposed()) {
                DumbService.getInstance(myProject).runWhenSmart(startRunnable);
              }
            }
          });
        }
      });
    }
  }

  @Override
  public void startRunProfile(@NotNull final RunProfileStarter starter,
                              @NotNull final RunProfileState state,
                              @NotNull final ExecutionEnvironment environment) {
    final Project project = environment.getProject();
    final RunContentDescriptor reuseContent = getContentManager().getReuseContent(environment);
    if (reuseContent != null) {
      reuseContent.setExecutionId(environment.getExecutionId());
    }

    final Executor executor = environment.getExecutor();
    project.getMessageBus().syncPublisher(EXECUTION_TOPIC).processStartScheduled(executor.getId(), environment);

    Runnable startRunnable = new Runnable() {
      @Override
      public void run() {
        if (project.isDisposed()) {
          return;
        }

        RunProfile profile = environment.getRunProfile();
        boolean started = false;
        try {
          project.getMessageBus().syncPublisher(EXECUTION_TOPIC).processStarting(executor.getId(), environment);

          final RunContentDescriptor descriptor = starter.execute(project, executor, state, reuseContent, environment);
          if (descriptor != null) {
            environment.setContentToReuse(descriptor);
            final Trinity<RunContentDescriptor, RunnerAndConfigurationSettings, Executor> trinity =
              Trinity.create(descriptor, environment.getRunnerAndConfigurationSettings(), executor);
            myRunningConfigurations.add(trinity);
            Disposer.register(descriptor, new Disposable() {
              @Override
              public void dispose() {
                myRunningConfigurations.remove(trinity);
              }
            });
            getContentManager().showRunContent(executor, descriptor, reuseContent);
            final ProcessHandler processHandler = descriptor.getProcessHandler();
            if (processHandler != null) {
              if (!processHandler.isStartNotified()) {
                processHandler.startNotify();
              }
              project.getMessageBus().syncPublisher(EXECUTION_TOPIC).processStarted(executor.getId(), environment, processHandler);
              started = true;
              processHandler.addProcessListener(new ProcessExecutionListener(project, profile, processHandler));
            }
          }
        }
        catch (ExecutionException e) {
          ExecutionUtil.handleExecutionError(project, executor.getToolWindowId(), profile, e);
          LOG.info(e);
        }
        finally {
          if (!started) {
            project.getMessageBus().syncPublisher(EXECUTION_TOPIC).processNotStarted(executor.getId(), environment);
          }
        }
      }
    };

    if (ApplicationManager.getApplication().isUnitTestMode()) {
      startRunnable.run();
    }
    else {
      compileAndRun(startRunnable, environment, state, new Runnable() {
        @Override
        public void run() {
          if (!project.isDisposed()) {
            project.getMessageBus().syncPublisher(EXECUTION_TOPIC).processNotStarted(executor.getId(), environment);
          }
        }
      });
    }
  }

  @Override
  public void restartRunProfile(@NotNull Project project,
                                @NotNull Executor executor,
                                @NotNull ExecutionTarget target,
                                @Nullable RunnerAndConfigurationSettings configuration,
                                @Nullable ProcessHandler processHandler) {
    ExecutionEnvironmentBuilder builder = createEnvironmentBuilder(project, executor, configuration);
    if (processHandler != null) {
      for (RunContentDescriptor descriptor : getContentManager().getAllDescriptors()) {
        if (descriptor.getProcessHandler() == processHandler) {
          builder.contentToReuse(descriptor);
          return;
        }
      }
    }
    restartRunProfile(builder.target(target).build());
  }

  @NotNull
  private static ExecutionEnvironmentBuilder createEnvironmentBuilder(@NotNull Project project, @NotNull Executor executor, @Nullable RunnerAndConfigurationSettings configuration) {
    ExecutionEnvironmentBuilder builder = new ExecutionEnvironmentBuilder(project, executor);

    ProgramRunner runner = RunnerRegistry.getInstance().getRunner(executor.getId(), configuration != null ? configuration.getConfiguration() : null);
    if (runner == null && configuration != null) {
      LOG.error("Cannot find runner for " + configuration.getName());
    }
    else if (runner != null) {
      assert configuration != null;
      builder.runnerAndSettings(runner, configuration);
    }

    return builder;
  }

  @Override
  public void restartRunProfile(@NotNull Project project,
                                @NotNull Executor executor,
                                @NotNull ExecutionTarget target,
                                @Nullable RunnerAndConfigurationSettings configuration,
                                @Nullable RunContentDescriptor currentDescriptor) {
    ExecutionEnvironmentBuilder builder = createEnvironmentBuilder(project, executor, configuration);
    restartRunProfile(builder.target(target).contentToReuse(currentDescriptor).build());
  }

  @Override
  public void restartRunProfile(@Nullable ProgramRunner runner,
                                @NotNull ExecutionEnvironment environment,
                                @Nullable RunContentDescriptor currentDescriptor) {
    ExecutionEnvironmentBuilder builder = new ExecutionEnvironmentBuilder(environment).contentToReuse(currentDescriptor);
    if (runner != null) {
      builder.runner(runner);
    }
    restartRunProfile(builder.build());
  }

  public static boolean isProcessRunning(@Nullable RunContentDescriptor descriptor) {
    ProcessHandler processHandler = descriptor == null ? null : descriptor.getProcessHandler();
    return processHandler != null && !processHandler.isProcessTerminated();
  }

  @Override
  public void restartRunProfile(@NotNull final ExecutionEnvironment environment) {
    RunnerAndConfigurationSettings configuration = environment.getRunnerAndConfigurationSettings();

    List<RunContentDescriptor> runningIncompatible;
    if (configuration == null) {
      runningIncompatible = Collections.emptyList();
    }
    else {
      runningIncompatible = getIncompatibleRunningDescriptors(configuration);
    }

    RunContentDescriptor contentToReuse = environment.getContentToReuse();
    final List<RunContentDescriptor> runningOfTheSameType = new SmartList<RunContentDescriptor>();
    if (configuration != null && configuration.isSingleton()) {
      runningOfTheSameType.addAll(getRunningDescriptorsOfTheSameConfigType(configuration));
    }
    else if (isProcessRunning(contentToReuse)) {
      runningOfTheSameType.add(contentToReuse);
    }

    List<RunContentDescriptor> runningToStop = ContainerUtil.concat(runningOfTheSameType, runningIncompatible);
    if (!runningToStop.isEmpty()) {
      if (configuration != null) {
        if (!runningOfTheSameType.isEmpty()
            && (runningOfTheSameType.size() > 1 || contentToReuse == null || runningOfTheSameType.get(0) != contentToReuse) &&
            !userApprovesStopForSameTypeConfigurations(environment.getProject(), configuration.getName(), runningOfTheSameType.size())) {
          return;
        }
        if (!runningIncompatible.isEmpty()
            && !userApprovesStopForIncompatibleConfigurations(myProject, configuration.getName(), runningIncompatible)) {
          return;
        }
      }

      for (RunContentDescriptor descriptor : runningToStop) {
        stop(descriptor);
      }
    }

    awaitingTerminationAlarm.addRequest(new Runnable() {
      @Override
      public void run() {
        if (environment.getRunner() != null && ExecutorRegistry.getInstance().isStarting(environment)) {
          awaitingTerminationAlarm.addRequest(this, 100);
          return;
        }

        for (RunContentDescriptor descriptor : runningOfTheSameType) {
          ProcessHandler processHandler = descriptor.getProcessHandler();
          if (processHandler != null && !processHandler.isProcessTerminated()) {
            awaitingTerminationAlarm.addRequest(this, 100);
            return;
          }
        }
        start(environment);
      }
    }, 50);
  }

  private static void start(@NotNull ExecutionEnvironment environment) {
    if (environment.getRunner() == null) {
      @SuppressWarnings("deprecation")
      Runnable restarter = environment.getContentToReuse() == null ? null : environment.getContentToReuse().getRestarter();
      if (restarter != null) {
        restarter.run();
      }
    }
    else {
      RunnerAndConfigurationSettings settings = environment.getRunnerAndConfigurationSettings();
      ProgramRunnerUtil.executeConfiguration(environment, settings != null && settings.isEditBeforeRun(), true);
    }
  }

  private static boolean userApprovesStopForSameTypeConfigurations(Project project, String configName, int instancesCount) {
    RunManagerImpl runManager = RunManagerImpl.getInstanceImpl(project);
    final RunManagerConfig config = runManager.getConfig();
    if (!config.isRestartRequiresConfirmation()) return true;

    DialogWrapper.DoNotAskOption option = new DialogWrapper.DoNotAskOption() {
      @Override
      public boolean isToBeShown() {
        return config.isRestartRequiresConfirmation();
      }

      @Override
      public void setToBeShown(boolean value, int exitCode) {
        config.setRestartRequiresConfirmation(value);
      }

      @Override
      public boolean canBeHidden() {
        return true;
      }

      @Override
      public boolean shouldSaveOptionsOnCancel() {
        return false;
      }

      @NotNull
      @Override
      public String getDoNotShowMessage() {
        return CommonBundle.message("dialog.options.do.not.show");
      }
    };
    return Messages.showOkCancelDialog(
      project,
      ExecutionBundle.message("rerun.singleton.confirmation.message", configName, instancesCount),
      ExecutionBundle.message("process.is.running.dialog.title", configName),
      ExecutionBundle.message("rerun.confirmation.button.text"),
      CommonBundle.message("button.cancel"),
      Messages.getQuestionIcon(), option) == Messages.OK;
  }

  private static boolean userApprovesStopForIncompatibleConfigurations(Project project,
                                                                       String configName,
                                                                       List<RunContentDescriptor> runningIncompatibleDescriptors) {
    RunManagerImpl runManager = RunManagerImpl.getInstanceImpl(project);
    final RunManagerConfig config = runManager.getConfig();
    if (!config.isStopIncompatibleRequiresConfirmation()) return true;

    DialogWrapper.DoNotAskOption option = new DialogWrapper.DoNotAskOption() {
      @Override
      public boolean isToBeShown() {
        return config.isStopIncompatibleRequiresConfirmation();
      }

      @Override
      public void setToBeShown(boolean value, int exitCode) {
        config.setStopIncompatibleRequiresConfirmation(value);
      }

      @Override
      public boolean canBeHidden() {
        return true;
      }

      @Override
      public boolean shouldSaveOptionsOnCancel() {
        return false;
      }

      @NotNull
      @Override
      public String getDoNotShowMessage() {
        return CommonBundle.message("dialog.options.do.not.show");
      }
    };

    final StringBuilder names = new StringBuilder();
    for (final RunContentDescriptor descriptor : runningIncompatibleDescriptors) {
      String name = descriptor.getDisplayName();
      if (names.length() > 0) {
        names.append(", ");
      }
      names.append(StringUtil.isEmpty(name) ? ExecutionBundle.message("run.configuration.no.name")
                                                       : String.format("'%s'", name));
    }

    //noinspection DialogTitleCapitalization
    return Messages.showOkCancelDialog(
      project,
      ExecutionBundle.message("stop.incompatible.confirmation.message",
                              configName, names.toString(), runningIncompatibleDescriptors.size()),
      ExecutionBundle.message("incompatible.configuration.is.running.dialog.title", runningIncompatibleDescriptors.size()),
      ExecutionBundle.message("stop.incompatible.confirmation.button.text"),
      CommonBundle.message("button.cancel"),
      Messages.getQuestionIcon(), option) == Messages.OK;
  }

  @NotNull
  private List<RunContentDescriptor> getRunningDescriptorsOfTheSameConfigType(@NotNull final RunnerAndConfigurationSettings configurationAndSettings) {
    return getRunningDescriptors(new Condition<RunnerAndConfigurationSettings>() {
      @Override
      public boolean value(@Nullable RunnerAndConfigurationSettings runningConfigurationAndSettings) {
        return configurationAndSettings == runningConfigurationAndSettings;
      }
    });
  }

  @NotNull
  private List<RunContentDescriptor> getIncompatibleRunningDescriptors(@NotNull RunnerAndConfigurationSettings configurationAndSettings) {
    final RunConfiguration configurationToCheckCompatibility = configurationAndSettings.getConfiguration();
    return getRunningDescriptors(new Condition<RunnerAndConfigurationSettings>() {
      @Override
      public boolean value(@Nullable RunnerAndConfigurationSettings runningConfigurationAndSettings) {
        RunConfiguration runningConfiguration = runningConfigurationAndSettings == null ? null : runningConfigurationAndSettings.getConfiguration();
        if (runningConfiguration == null || !(runningConfiguration instanceof CompatibilityAwareRunProfile)) {
          return false;
        }
        return ((CompatibilityAwareRunProfile)runningConfiguration).mustBeStoppedToRun(configurationToCheckCompatibility);
      }
    });
  }

  @NotNull
  private List<RunContentDescriptor> getRunningDescriptors(@NotNull Condition<RunnerAndConfigurationSettings> condition) {
    List<RunContentDescriptor> result = new SmartList<RunContentDescriptor>();
    for (Trinity<RunContentDescriptor, RunnerAndConfigurationSettings, Executor> trinity : myRunningConfigurations) {
      if (condition.value(trinity.getSecond())) {
        ProcessHandler processHandler = trinity.getFirst().getProcessHandler();
        if (processHandler != null && !processHandler.isProcessTerminating() && !processHandler.isProcessTerminated()) {
          result.add(trinity.getFirst());
        }
      }
    }
    return result;
  }

  private static void stop(@Nullable RunContentDescriptor descriptor) {
    ProcessHandler processHandler = descriptor != null ? descriptor.getProcessHandler() : null;
    if (processHandler == null) {
      return;
    }

    if (processHandler instanceof KillableProcess && processHandler.isProcessTerminating()) {
      ((KillableProcess)processHandler).killProcess();
      return;
    }

    if (!processHandler.isProcessTerminated()) {
      if (processHandler.detachIsDefault()) {
        processHandler.detachProcess();
      }
      else {
        processHandler.destroyProcess();
      }
    }
  }

  @Override
  @NotNull
  public String getComponentName() {
    return "ExecutionManager";
  }

  private static class ProcessExecutionListener extends ProcessAdapter {
    private final Project myProject;
    private final RunProfile myProfile;
    private final ProcessHandler myProcessHandler;

    public ProcessExecutionListener(Project project, RunProfile profile, ProcessHandler processHandler) {
      myProject = project;
      myProfile = profile;
      myProcessHandler = processHandler;
    }

    @Override
    public void processTerminated(ProcessEvent event) {
      if (myProject.isDisposed()) return;

      myProject.getMessageBus().syncPublisher(EXECUTION_TOPIC).processTerminated(myProfile, myProcessHandler);
      VirtualFileManager.getInstance().asyncRefresh(null);
    }

    @Override
    public void processWillTerminate(ProcessEvent event, boolean willBeDestroyed) {
      if (myProject.isDisposed()) return;

      myProject.getMessageBus().syncPublisher(EXECUTION_TOPIC).processTerminating(myProfile, myProcessHandler);
    }
  }
}
