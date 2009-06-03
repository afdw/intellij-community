package com.intellij.ide.util.newProjectWizard;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolder;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author nik
 */
public interface FrameworkSupportModel extends UserDataHolder {

  @Nullable
  Project getProject();

  boolean isFrameworkSelected(@NotNull @NonNls String providerId);

  void addFrameworkListener(@NotNull FrameworkSupportModelListener listener);

  void removeFrameworkListener(@NotNull FrameworkSupportModelListener listener);

  void setFrameworkComponentEnabled(@NotNull @NonNls String providerId, boolean enabled);

  FrameworkSupportConfigurable getFrameworkConfigurable(@NotNull @NonNls String providerId);
}
