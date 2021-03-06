/*
 * Copyright 2018 The StartupOS Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.startupos.tools.reviewer.aa.commands;

import com.google.common.collect.ImmutableList;
import com.google.startupos.tools.reviewer.localserver.service.Protos.GithubPr;
import com.google.startupos.tools.reviewer.localserver.service.Protos.Empty;
import com.google.startupos.common.FileUtils;
import com.google.startupos.common.flags.Flag;
import com.google.startupos.common.flags.FlagDesc;
import com.google.startupos.common.flags.Flags;
import com.google.startupos.common.repo.GitRepo;
import com.google.startupos.common.repo.GitRepoFactory;
import com.google.startupos.tools.reviewer.localserver.service.CodeReviewServiceGrpc;
import com.google.startupos.tools.reviewer.localserver.service.Protos.CreateDiffRequest;
import com.google.startupos.tools.reviewer.localserver.service.Protos.Diff;
import com.google.startupos.tools.reviewer.localserver.service.Protos.DiffNumberResponse;
import com.google.startupos.tools.reviewer.localserver.service.Protos.DiffRequest;
import com.google.startupos.tools.reviewer.localserver.service.Protos.Reviewer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.nio.file.Paths;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DiffCommand implements AaCommand {
  private final FileUtils fileUtils;
  private final GitRepoFactory gitRepoFactory;
  private String workspaceName;
  private String workspacePath;
  private Integer diffNumber;

  private static final Integer GRPC_PORT = 8001;

  private final CodeReviewServiceGrpc.CodeReviewServiceBlockingStub codeReviewBlockingStub;

  @FlagDesc(name = "reviewers", description = "Reviewers (split by comma)")
  static Flag<String> reviewers = Flag.create("");

  @FlagDesc(name = "description", description = "Description")
  static Flag<String> description = Flag.create("");

  @FlagDesc(name = "buglink", description = "Buglink")
  static Flag<String> buglink = Flag.create("");

  @Inject
  public DiffCommand(
      FileUtils utils,
      GitRepoFactory repoFactory,
      @Named("Workspace name") String workspaceName,
      @Named("Workspace path") String workspacePath,
      @Named("Diff number") Integer diffNumber) {
    this.fileUtils = utils;
    this.gitRepoFactory = repoFactory;
    this.workspaceName = workspaceName;
    this.workspacePath = workspacePath;
    this.diffNumber = diffNumber;

    ManagedChannel channel =
        ManagedChannelBuilder.forAddress("localhost", GRPC_PORT).usePlaintext().build();
    codeReviewBlockingStub = CodeReviewServiceGrpc.newBlockingStub(channel);
  }

  private ImmutableList<Reviewer> getReviewers(String reviewersInput) {
    return ImmutableList.copyOf(
        Arrays.stream(reviewersInput.split(","))
            .filter(x -> !x.equals(""))
            .map(reviewer -> Reviewer.newBuilder().setEmail(reviewer.trim()).build())
            .collect(Collectors.toList()));
  }

  private ImmutableList<String> getIssues(String issuesInput) {
    return ImmutableList.copyOf(
        Arrays.stream(issuesInput.split(","))
            .map(String::trim)
            .filter(x -> !x.isEmpty())
            .collect(Collectors.toList()));
  }

  private Diff createDiff() {
    DiffNumberResponse response =
        codeReviewBlockingStub.getAvailableDiffNumber(Empty.getDefaultInstance());
    String branchName = String.format("D%s", response.getLastDiffId());
    System.out.println("Creating " + branchName);

    Long currentTime = new Long(System.currentTimeMillis());

    Diff.Builder diffBuilder =
        Diff.newBuilder()
            .setWorkspace(workspaceName)
            .setDescription(description.get())
            .addAllIssue(getIssues(buglink.get()))
            .addAllReviewer(getReviewers(reviewers.get()))
            .setId(response.getLastDiffId())
            .setCreatedTimestamp(currentTime)
            .setModifiedTimestamp(currentTime);

    Map<GitRepo, String> repoToInitialBranch = new HashMap<>();
    try {
      fileUtils
          .listContents(workspacePath)
          .stream()
          .map(path -> fileUtils.joinToAbsolutePath(workspacePath, path))
          .filter(fileUtils::folderExists)
          .forEach(
              path -> {
                String repoName = Paths.get(path).getFileName().toString();
                GitRepo repo = this.gitRepoFactory.create(path);
                repoToInitialBranch.put(repo, repo.currentBranch());
                System.out.println(
                    String.format("[%s/%s]: switching to diff branch", workspaceName, repoName));
                repo.switchBranch(branchName);
              });
      addGithubRepos(diffBuilder);
    } catch (Exception e) {
      repoToInitialBranch.forEach(
          (repo, initialBranch) -> {
            if (!repo.currentBranch().equals(initialBranch)) {
              repo.switchBranch(initialBranch);
            }
          });
      e.printStackTrace();
    }
    return diffBuilder.build();
  }

  /* Update diff metadata - reviewers, description, buglink */
  private Diff updateDiff(Integer diffNumber) {
    System.out.println(String.format("Updating D%d", diffNumber));

    Diff.Builder diffBuilder =
        codeReviewBlockingStub
            .getDiff(DiffRequest.newBuilder().setDiffId(diffNumber).build())
            .toBuilder();
    if (!reviewers.get().isEmpty()) {
      // adding specified reviewers
      diffBuilder.addAllReviewer(getReviewers(reviewers.get()));
    }

    if (!description.get().isEmpty()) {
      // replace description if specified
      diffBuilder.setDescription(description.get());
    }

    if (!buglink.get().isEmpty()) {
      // replace buglink if specified
      diffBuilder.addAllIssue(getIssues(buglink.get()));
    }

    addGithubRepos(diffBuilder);
    diffBuilder.setModifiedTimestamp(new Long(System.currentTimeMillis()));

    return diffBuilder.build();
  }

  @Override
  public boolean run(String[] args) {
    Flags.parseCurrentPackage(args);

    Diff diff = (diffNumber == -1) ? createDiff() : updateDiff(diffNumber);
    CreateDiffRequest request = CreateDiffRequest.newBuilder().setDiff(diff).build();
    // TODO: Check if we changed diff, update only if changed. Write to output if no change.
    // TODO: Rename createDiff to createOrUpdateDiff
    codeReviewBlockingStub.createDiff(request);
    return true;
  }

  private void addGithubRepos(Diff.Builder diffBuilder) {
    List<String> existingGithubRepoNames =
        diffBuilder.getGithubPrList().stream().map(GithubPr::getRepo).collect(Collectors.toList());
    try {
      fileUtils
          .listContents(workspacePath)
          .stream()
          .map(path -> fileUtils.joinToAbsolutePath(workspacePath, path))
          .filter(fileUtils::folderExists)
          .forEach(
              path -> {
                GitRepo repo = this.gitRepoFactory.create(path);
                if (repo.hasChanges(repo.currentBranch())) {
                  // Example of repoURL: https://github.com/google/startup-os.git
                  String repoURL = repo.getRemoteURL();
                  String repoOwner = repoURL.split("/")[3];
                  String repoName = repoURL.split("/")[4].replace(".git", "").trim();

                  String folderName = Paths.get(path).getFileName().toString();
                  if (!repoName.equals(folderName)) {
                    System.out.println(
                        String.format(
                            "Repository name from the URL(%s) and folder name from workspace(%s) aren't the same.",
                            repoName, folderName));
                  }

                  if (!existingGithubRepoNames.contains(repoName)) {
                    diffBuilder.addGithubPr(
                        GithubPr.newBuilder().setRepo(repoName).setOwner(repoOwner).build());
                  }
                }
              });
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}

