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
package com.intellij.vcs.log.impl;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.LocalFilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.Hash;
import com.intellij.vcs.log.VcsFullCommitDetails;
import com.intellij.vcs.log.VcsShortCommitDetails;
import com.intellij.vcs.log.VcsUser;
import com.intellij.vcs.log.impl.VcsStatusMerger.MergedStatusInfo;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * Allows to postpone changes parsing, which might take long for a large amount of commits,
 * because {@link Change} holds {@link LocalFilePath} which makes costly refreshes and type detections.
 */
@ApiStatus.Experimental
public class VcsChangesLazilyParsedDetails extends VcsCommitMetadataImpl implements VcsFullCommitDetails {
  private static final Logger LOG = Logger.getInstance(VcsChangesLazilyParsedDetails.class);
  protected static final Changes EMPTY_CHANGES = new EmptyChanges();
  @NotNull private final ChangesParser myChangesParser;
  @NotNull private final AtomicReference<Changes> myChanges = new AtomicReference<>();

  public VcsChangesLazilyParsedDetails(@NotNull Project project, @NotNull Hash hash, @NotNull List<Hash> parents, long commitTime,
                                       @NotNull VirtualFile root,
                                       @NotNull String subject, @NotNull VcsUser author, @NotNull String message,
                                       @NotNull VcsUser committer, long authorTime,
                                       @NotNull List<List<VcsFileStatusInfo>> reportedChanges,
                                       @NotNull ChangesParser changesParser) {
    super(hash, parents, commitTime, root, subject, author, message, committer, authorTime);
    myChangesParser = changesParser;
    myChanges.set(reportedChanges.isEmpty() ? EMPTY_CHANGES : new UnparsedChanges(project, reportedChanges, (sources, parent) -> {
      return myChangesParser.parseStatusInfo(project, this, sources, parent);
    }));
  }

  @NotNull
  @Override
  public Collection<Change> getChanges() {
    return myChanges.get().getMergedChanges();
  }

  @NotNull
  @Override
  public Collection<Change> getChanges(int parent) {
    return myChanges.get().getChanges(parent);
  }

  public int size() {
    int size = 0;
    Changes changes = myChanges.get();
    if (changes instanceof UnparsedChanges) {
      for (int i = 0; i < getParents().size(); i++) {
        size += ((UnparsedChanges)changes).getChangesOutput().get(i).size();
      }
    }
    else {
      for (int i = 0; i < getParents().size(); i++) {
        size += changes.getChanges(i).size();
      }
    }
    return size;
  }

  protected interface Changes {
    @NotNull
    Collection<Change> getMergedChanges();

    @NotNull
    Collection<Change> getChanges(int parent);
  }

  protected static class EmptyChanges implements Changes {
    @NotNull
    @Override
    public Collection<Change> getMergedChanges() {
      return ContainerUtil.emptyList();
    }

    @NotNull
    @Override
    public Collection<Change> getChanges(int parent) {
      return ContainerUtil.emptyList();
    }
  }

  protected class UnparsedChanges implements Changes {
    @NotNull protected final Project myProject;
    @NotNull protected final List<List<VcsFileStatusInfo>> myChangesOutput;
    @NotNull private final VcsStatusMerger<VcsFileStatusInfo> myStatusMerger = new VcsFileStatusInfoMerger();
    @NotNull private final BiFunction<List<VcsFileStatusInfo>, Integer, List<Change>> myParser;

    public UnparsedChanges(@NotNull Project project,
                           @NotNull List<List<VcsFileStatusInfo>> changesOutput,
                           @NotNull BiFunction<List<VcsFileStatusInfo>, Integer, List<Change>> parser) {
      myProject = project;
      myChangesOutput = changesOutput;
      myParser = parser;
    }

    @NotNull
    protected ParsedChanges parseChanges() {
      List<Change> mergedChanges = parseMergedChanges();
      List<Collection<Change>> changes = computeChanges(mergedChanges);
      ParsedChanges parsedChanges = new ParsedChanges(mergedChanges, changes);
      myChanges.compareAndSet(this, parsedChanges);
      return parsedChanges;
    }

    @NotNull
    private List<Change> parseMergedChanges() {
      List<MergedStatusInfo<VcsFileStatusInfo>> statuses = getMergedStatusInfo();
      List<Change> changes = myParser.apply(ContainerUtil.map(statuses, MergedStatusInfo::getStatusInfo), 0);
      LOG.assertTrue(changes.size() == statuses.size(), "Incorrectly parsed statuses " + statuses + " to changes " + changes);
      if (getParents().size() <= 1) return changes;

      // each merge change knows about all changes to parents
      List<Change> wrappedChanges = new ArrayList<>(statuses.size());
      for (int i = 0; i < statuses.size(); i++) {
        wrappedChanges.add(new MyMergedChange(changes.get(i), statuses.get(i), myParser));
      }
      return wrappedChanges;
    }

    @NotNull
    @Override
    public Collection<Change> getMergedChanges() {
      return parseChanges().getMergedChanges();
    }

    @NotNull
    @Override
    public Collection<Change> getChanges(int parent) {
      return parseChanges().getChanges(parent);
    }

    @NotNull
    private List<Collection<Change>> computeChanges(@NotNull Collection<Change> mergedChanges) {
      if (myChangesOutput.size() == 1) {
        return Collections.singletonList(mergedChanges);
      }
      else {
        List<Collection<Change>> changes = ContainerUtil.newArrayListWithCapacity(myChangesOutput.size());
        for (int i = 0; i < myChangesOutput.size(); i++) {
          changes.add(myParser.apply(myChangesOutput.get(i), i));
        }
        return changes;
      }
    }

    /*
     * This method mimics result of `-c` option added to `git log` command.
     * It calculates statuses for files that were modified in all parents of a merge commit.
     * If a commit is not a merge, all statuses are returned.
     */
    @NotNull
    private List<MergedStatusInfo<VcsFileStatusInfo>> getMergedStatusInfo() {
      return myStatusMerger.merge(myChangesOutput);
    }

    @NotNull
    public List<List<VcsFileStatusInfo>> getChangesOutput() {
      return myChangesOutput;
    }
  }

  private static class MyMergedChange extends MergedChange {
    @NotNull private final Supplier<List<Change>> mySourceChanges;

    MyMergedChange(@NotNull Change change, @NotNull MergedStatusInfo<VcsFileStatusInfo> statusInfo,
                   @NotNull BiFunction<List<VcsFileStatusInfo>, Integer, List<Change>> parser) {
      super(change);
      mySourceChanges = Suppliers.memoize(() -> {
        List<Change> sourceChanges = ContainerUtil.newArrayList();
        for (int parent = 0; parent < statusInfo.getMergedStatusInfos().size(); parent++) {
          List<VcsFileStatusInfo> statusInfos = Collections.singletonList(statusInfo.getMergedStatusInfos().get(parent));
          sourceChanges.addAll(parser.apply(statusInfos, parent));
        }
        return sourceChanges;
      });
    }

    @Override
    public List<Change> getSourceChanges() {
      return mySourceChanges.get();
    }
  }

  protected static class ParsedChanges implements Changes {
    @NotNull private final Collection<Change> myMergedChanges;
    @NotNull private final List<? extends Collection<Change>> myChanges;

    ParsedChanges(@NotNull Collection<Change> mergedChanges, @NotNull List<? extends Collection<Change>> changes) {
      myMergedChanges = mergedChanges;
      myChanges = changes;
    }

    @NotNull
    @Override
    public Collection<Change> getMergedChanges() {
      return myMergedChanges;
    }

    @NotNull
    @Override
    public Collection<Change> getChanges(int parent) {
      return myChanges.get(parent);
    }
  }

  public interface ChangesParser {
    List<Change> parseStatusInfo(@NotNull Project project,
                                 @NotNull VcsShortCommitDetails commit,
                                 @NotNull List<VcsFileStatusInfo> changes,
                                 int parentIndex);
  }
}
