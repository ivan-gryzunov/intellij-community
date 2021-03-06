// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.changes.committed;

import com.google.common.collect.Iterables;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.RepositoryLocation;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class CommittedListsSequencesZipper {

  @NotNull private final VcsCommittedListsZipper myVcsPartner;
  @NotNull private final List<RepositoryLocation> myInLocations;
  @NotNull private final Map<String, List<CommittedChangeList>> myInLists;
  @NotNull private final Comparator<CommittedChangeList> myComparator;

  public CommittedListsSequencesZipper(@NotNull VcsCommittedListsZipper vcsPartner) {
    myVcsPartner = vcsPartner;
    myInLocations = ContainerUtil.newArrayList();
    myInLists = new HashMap<>();
    myComparator = (o1, o2) -> Comparing.compare(myVcsPartner.getNumber(o1), myVcsPartner.getNumber(o2));
  }

  public void add(@NotNull RepositoryLocation location, @NotNull List<CommittedChangeList> lists) {
    myInLocations.add(location);
    Collections.sort(lists, myComparator);
    myInLists.put(location.toPresentableString(), lists);
  }

  @NotNull
  public List<CommittedChangeList> execute() {
    Pair<List<RepositoryLocationGroup>, List<RepositoryLocation>> groupingResult = myVcsPartner.groupLocations(myInLocations);
    List<CommittedChangeList> result = ContainerUtil.newArrayList();

    result.addAll(ContainerUtil.flatten(collectChangeLists(groupingResult.getSecond())));
    for (RepositoryLocationGroup group : groupingResult.getFirst()) {
      result.addAll(mergeLocationGroupChangeLists(group));
    }

    return result;
  }

  @NotNull
  private List<List<CommittedChangeList>> collectChangeLists(@NotNull List<RepositoryLocation> locations) {
    List<List<CommittedChangeList>> result = ContainerUtil.newArrayListWithCapacity(locations.size());

    for (RepositoryLocation location : locations) {
      result.add(myInLists.get(location.toPresentableString()));
    }

    return result;
  }

  @NotNull
  private List<CommittedChangeList> mergeLocationGroupChangeLists(@NotNull RepositoryLocationGroup group) {
    List<CommittedChangeList> result = ContainerUtil.newArrayList();
    List<CommittedChangeList> equalLists = ContainerUtil.newArrayList();
    CommittedChangeList previousList = null;

    for (CommittedChangeList list : Iterables.mergeSorted(collectChangeLists(group.getLocations()), myComparator)) {
      if (previousList != null && myComparator.compare(previousList, list) != 0) {
        result.add(zip(group, equalLists));
        equalLists.clear();
      }
      equalLists.add(list);
      previousList = list;
    }
    if (!equalLists.isEmpty()) {
      result.add(zip(group, equalLists));
    }

    return result;
  }

  @NotNull
  private CommittedChangeList zip(@NotNull RepositoryLocationGroup group, @NotNull List<CommittedChangeList> equalLists) {
    if (equalLists.isEmpty()) {
      throw new IllegalArgumentException("equalLists can not be empty");
    }

    return equalLists.size() > 1 ? myVcsPartner.zip(group, equalLists) : equalLists.get(0);
  }
}
