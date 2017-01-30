package com.parallels.bitbucket.plugins.defaultreviewers;

import com.atlassian.bitbucket.hook.repository.RepositoryMergeRequestCheck;
import com.atlassian.bitbucket.hook.repository.RepositoryMergeRequestCheckContext;
import com.atlassian.bitbucket.scm.pull.MergeRequest;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestChangesRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.pull.UnmodifiablePullRequestRoleException;
import com.atlassian.bitbucket.content.AbstractChangeCallback;
import com.atlassian.bitbucket.content.Change;
import com.atlassian.bitbucket.content.ChangeContext;
import com.atlassian.bitbucket.content.ChangeSummary;
import com.atlassian.bitbucket.scm.git.command.GitCommandBuilderFactory;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.io.SingleLineOutputHandler;

import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.bitbucket.user.UserService;
import com.atlassian.bitbucket.util.Operation;
import com.atlassian.bitbucket.user.SecurityService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import java.util.logging.Logger;


public class DefaultReviewersHook implements RepositoryMergeRequestCheck {
  private final PullRequestService pullRequestService;
  private final GitCommandBuilderFactory builderFactory;
  private final SecurityService securityService;
  private final UserService userService;
  private static final Logger log = Logger.getLogger(DefaultReviewersHook.class.getName());

  public DefaultReviewersHook (
    final PullRequestService pullRequestService,
    final GitCommandBuilderFactory builderFactory,
    final SecurityService securityService,
    final UserService userService
  ) {
    this.pullRequestService = pullRequestService;
    this.builderFactory = builderFactory;
    this.securityService = securityService;
    this.userService = userService;
  }

  @Override
  public void check(RepositoryMergeRequestCheckContext context) {
    final MergeRequest mergeRequest = context.getMergeRequest();
    final PullRequest pullRequest = mergeRequest.getPullRequest();
    final Repository repo = pullRequest.getToRef().getRepository();

    final String newSha = pullRequest.getFromRef().getLatestCommit();

    Path gitIndex = null;
    try {
        gitIndex = Files.createTempFile("git_idx_", "");
        final String gitIndexPath = gitIndex.toString();

        builderFactory.builder(repo)
          .command("read-tree")
          .argument(newSha)
          .withEnvironment("GIT_INDEX_FILE", gitIndexPath)
          .build(new SingleLineOutputHandler())
          .call();

        pullRequestService.streamChanges(
          new PullRequestChangesRequest.Builder(pullRequest).build(),
          new AbstractChangeCallback() {
            public boolean onChange(Change change) throws IOException {
              String changedFile = change.getPath().toString();
              List<String> ownersList = builderFactory.builder(repo)
                                    .command("check-attr")
                                    .argument("--cached")
                                    .argument("owners")
                                    .argument("--")
                                    .argument(changedFile)
                                    .withEnvironment("GIT_INDEX_FILE", gitIndexPath)
                                    .build(new GitCheckAttrOutputHandler())
                                    .call();
              if (ownersList == null) {
                return true;
              }
              for (String owner : ownersList) {
                ApplicationUser ownerUser = getUserByNameOrEmail(owner);
                if (ownerUser == null) {
                  log.warning("User not found by name or email: " + owner);
                } else {
                  try {
                    pullRequestService.addReviewer(repo.getId(), pullRequest.getId(), ownerUser.getName());
                  } catch (UnmodifiablePullRequestRoleException ignore) {
                    // noop, user is the pull request author
                  } catch (Exception e) {
                    log.severe("Failed to add reviewer: " + e.toString());
                  }
                }
              }
              return true;
            }
            public void onEnd(ChangeSummary summary) throws IOException {
              // noop
            }
            public void onStart(ChangeContext context) throws IOException {
              // noop
            }
          }
        );
    } catch (IOException e) {
      log.severe(e.toString());
    } finally {
      try {
        Files.delete(gitIndex);
      } catch (Exception e) { // IOException, NullPointerException
        log.warning(e.toString());
      }
    }
  }

  public ApplicationUser getUserByNameOrEmail(final String userNameOrEmail) {
    ApplicationUser user = null;

    try {
      user = securityService.withPermission(Permission.REPO_ADMIN, "Find user").call(
        new Operation<ApplicationUser, Exception>() {
          @Override
          public ApplicationUser perform() throws Exception {
            return userService.findUserByNameOrEmail(userNameOrEmail);
          }
        }
      );
    } catch (Exception e) {
      log.severe(e.toString());
      return null;
    }

    return user;
  }

}