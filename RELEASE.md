# com.intellij.platform:kotlinx-coroutines-* release

`master` branch is the upstream `master` with all the patches applied.

```
# update branches
# git remote add upstream https://github.com/Kotlin/kotlinx.coroutines.git 
git checkout master
git fetch upstream
git fetch origin

# if new patch commits need to be cherry-picked without main library version upgrade, cherry-pick them to master branch

# if library version has changed, reapply all the patches on new upstream master
# update intellij/patch-base
git checkout intellij/patch-base
git rebase upstream/master
# apply patches to new master
git checkout master
git reset --hard upstream/master
git rebase intellij/patch-base
# also rebase patch branches on top of new intellij/patch-base[, maybe squash them for cherry-pick], then cherry-pick the patches
git cherry-pick <patch commits from intellij/whatever-patches-we-have>, see IntelliJ-patches.md of the last published version for the list of patches
git push origin master --force

# prepare a release branch from the master branch
git checkout -b release/<version>, e.g., release/1.8.0-intellij-6
# change the version in `gradle.properties`, e.g., to 1.8.0-intellij-6
# commit version change
git push origin release/<version>
```

---

# kotlinx.coroutines release checklist

To release a new `<version>` of `kotlinx-coroutines`:

1. Checkout the `develop` branch: <br>
   `git checkout develop`

2. Retrieve the most recent `develop`: <br>
   `git pull`

3. Make sure the `master` branch is fully merged into `develop`:
   `git merge origin/master`

4. Search & replace `<old-version>` with `<version>` across the project files. Should replace in:
   * Docs
     * [`README.md`](README.md) (native, core, test, debug, modules)
     * [`kotlinx-coroutines-debug/README.md`](kotlinx-coroutines-debug/README.md)
     * [`kotlinx-coroutines-test/README.md`](kotlinx-coroutines-test/README.md)
     * [`coroutines-guide-ui.md`](ui/coroutines-guide-ui.md)
   * Properties
     * [`gradle.properties`](gradle.properties)
     * [`integration-testing/gradle.properties`](integration-testing/gradle.properties)
   * Make sure to **exclude** `CHANGES.md` from replacements.

   As an alternative approach, you can use `./bump-version.sh new_version`

5. Write release notes in [`CHANGES.md`](CHANGES.md):
   * Use the old releases for style guidance.
   * Write each change on a single line (don't wrap with CR).
   * Look through the commit messages since the previous release.

6. Create the branch for this release:
   `git checkout -b version-<version>`

7. Commit the updated files to the new version branch:<br>
   `git commit -a -m "Version <version>"`

8. Push the new version to GitHub:<br>
   `git push -u origin version-<version>`

9. Create a Pull-Request on GitHub from the `version-<version>` branch into `master`:
   * Review it.
   * Make sure it builds on CI.
   * Get approval for it.

0. On [TeamCity integration server](https://teamcity.jetbrains.com/project.html?projectId=KotlinTools_KotlinxCoroutines):
   * Wait until "Build" configuration for committed `version-<version>` branch passes tests.
   * Run "Deploy (Configure, RUN THIS ONE)" configuration with the corresponding new version:
     - Use the `version-<version>` branch
     - Set the `DeployVersion` build parameter to `<version>`
   * Wait until all four "Deploy" configurations finish.

1. In [Nexus](https://oss.sonatype.org/#stagingRepositories) admin interface:
   * Close the repository and wait for it to verify.
   * Release the repository.

2. Merge the new version branch into `master`:<br>
   `git checkout master`<br>
   `git merge version-<version>`<br>
   `git push`

3. In [GitHub](https://github.com/kotlin/kotlinx.coroutines) interface:
   * Create a release named `<version>`, creating the `<version>` tag.
   * Cut & paste lines from [`CHANGES.md`](CHANGES.md) into description.

4. Announce the new release in [Slack](https://kotlinlang.slack.com)

5. Switch onto the `develop` branch:<br>
   `git checkout develop`

6. Fetch the latest `master`:<br>
   `git fetch`

7. Merge the release from `master`:<br>
   `git merge origin/master`

8. Push the updates to GitHub:<br>
   `git push`

9. Propose the website documentation update: <br>
   * Set new value for [`KOTLINX_COROUTINES_RELEASE_TAG`](https://github.com/JetBrains/kotlin-web-site/blob/master/.teamcity/BuildParams.kt), creating a Pull Request in the website's repository. 
