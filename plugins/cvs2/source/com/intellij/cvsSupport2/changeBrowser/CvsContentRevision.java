/*
 * Copyright (c) 2000-2006 JetBrains s.r.o. All Rights Reserved.
 */

/*
 * Created by IntelliJ IDEA.
 * User: yole
 * Date: 28.11.2006
 * Time: 20:54:15
 */
package com.intellij.cvsSupport2.changeBrowser;

import com.intellij.cvsSupport2.connections.CvsEnvironment;
import com.intellij.cvsSupport2.cvsoperations.cvsContent.GetFileContentOperation;
import com.intellij.cvsSupport2.cvsoperations.dateOrRevision.SimpleRevision;
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutor;
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutorCallback;
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler;
import com.intellij.cvsSupport2.history.CvsRevisionNumber;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.cvsIntegration.CvsResult;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.CvsBundle;
import com.intellij.peer.PeerFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.UnsupportedEncodingException;

public class CvsContentRevision implements ContentRevision {
  private final String myRevisionNumber;
  private final File myFile;
  private final CvsEnvironment myEnvironment;
  private final Project myProject;

  private String myContent;

  public CvsContentRevision(final File file,
                            final String revisionNumber,
                            final CvsEnvironment environment,
                            final Project project) {
    myFile = file;
    myRevisionNumber= revisionNumber;
    myEnvironment = environment;
    myProject = project;
  }

  @Nullable
  public String getContent() throws VcsException {
    if (myContent == null) {
      myContent = loadContent();
    }
    return myContent;
  }

  private String loadContent() throws VcsException {
    final GetFileContentOperation operation = new GetFileContentOperation(myFile, myEnvironment, new SimpleRevision(myRevisionNumber));
    CvsOperationExecutor executor = new CvsOperationExecutor(myProject);
    executor.performActionSync(new CommandCvsHandler(CvsBundle.message("operation.name.load.file"),
                                                     operation),
                               CvsOperationExecutorCallback.EMPTY);
    CvsResult result = executor.getResult();
    if (result.isCanceled()) {
      throw new ProcessCanceledException();
    }
    if (!result.hasNoErrors()) {
      throw result.composeError();
    }

    try {
      return new String(operation.getFileBytes(), CharsetToolkit.getIDEOptionsCharset().name());
    }
    catch (UnsupportedEncodingException e) {
      throw new VcsException(e);
    }
  }

  @NotNull
  public FilePath getFile() {
    return PeerFactory.getInstance().getVcsContextFactory().createFilePathOn(myFile);
  }

  @NotNull
  public VcsRevisionNumber getRevisionNumber() {
    return new CvsRevisionNumber(myRevisionNumber);
  }
}