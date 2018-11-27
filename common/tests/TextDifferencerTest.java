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

package com.google.startupos.common;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.TextFormat;
import com.google.startupos.common.Protos.ChangeType;
import com.google.startupos.common.Protos.TextChange;
import com.google.startupos.common.Protos.TextDiff;
import dagger.Component;
import javax.inject.Singleton;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link com.google.startupos.common.TextDifferencer}. */
@RunWith(JUnit4.class)
public class TextDifferencerTest {

  private TextDifferencer differencer;
  private FileUtils fileUtils;

  private TextChange textChange(
      String text, ChangeType type, int lineNumber, int startIndex, int endIndex) {
    return TextChange.newBuilder()
        .setText(text)
        .setType(type)
        .setLineNumber(lineNumber)
        .setStartIndex(startIndex)
        .setEndIndex(endIndex)
        .build();
  }

  @Before
  public void setUp() {
    differencer = new TextDifferencer();
    fileUtils = DaggerTextDifferencerTest_TestComponent.create().getFileUtils();
  }

  protected String readFile(String filename) {
    return fileUtils.readFileFromResourcesUnchecked("common/tests/resources/" + filename);
  }

  protected TextDiff readTextDiff(String filename) {
    String protoText = readFile(filename);
    TextDiff.Builder result = TextDiff.newBuilder();
    try {
      TextFormat.merge(protoText, result);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return result.build();
  }

  @Test
  public void testEmptyDiff() {
    assertEquals(TextDiff.getDefaultInstance(), differencer.getTextDiff("", ""));
  }

  @Test
  public void testLeftAndRightAreEqual() {
    String text = "aaa";

    TextDiff expectedTextDiff =
        TextDiff.newBuilder()
            .addLeftChange(
                TextChange.newBuilder()
                    .setText(text)
                    .setType(ChangeType.NO_CHANGE)
                    .setEndIndex(3)
                    .build())
            .addRightChange(
                TextChange.newBuilder()
                    .setText(text)
                    .setType(ChangeType.NO_CHANGE)
                    .setEndIndex(3)
                    .build())
            .setLeftFileContents(text)
            .setRightFileContents(text)
            .build();
    assertEquals(expectedTextDiff, differencer.getTextDiff(text, text));
  }

  @Test
  public void testOnlyAdditions() {
    String leftContents = "";
    String rightContents = "Addition.";

    TextDiff expectedTextDiff =
        TextDiff.newBuilder()
            .addRightChange(
                TextChange.newBuilder()
                    .setText(rightContents)
                    .setType(ChangeType.ADD)
                    .setEndIndex(9)
                    .build())
            .setLeftFileContents(leftContents)
            .setRightFileContents(rightContents)
            .build();
    assertEquals(expectedTextDiff, differencer.getTextDiff(leftContents, rightContents));
  }

  @Test
  public void testOnlyDeletions() {
    String leftContents = "Deletion.";
    String rightContents = "";

    TextDiff expectedTextDiff =
        TextDiff.newBuilder()
            .addLeftChange(
                TextChange.newBuilder()
                    .setText(leftContents)
                    .setType(ChangeType.DELETE)
                    .setEndIndex(9)
                    .build())
            .setLeftFileContents(leftContents)
            .setRightFileContents(rightContents)
            .build();
    assertEquals(expectedTextDiff, differencer.getTextDiff(leftContents, rightContents));
  }

  @Test
  public void testOnlyNoChanges() {
    String contents = "No Change.";

    TextDiff expectedTextDiff =
        TextDiff.newBuilder()
            .addLeftChange(
                TextChange.newBuilder()
                    .setText(contents)
                    .setType(ChangeType.NO_CHANGE)
                    .setEndIndex(10)
                    .build())
            .addRightChange(
                TextChange.newBuilder()
                    .setText(contents)
                    .setType(ChangeType.NO_CHANGE)
                    .setEndIndex(10)
                    .build())
            .setLeftFileContents(contents)
            .setRightFileContents(contents)
            .build();
    assertEquals(expectedTextDiff, differencer.getTextDiff(contents, contents));
  }

  @Test
  public void testMixedChangesAtTheBeginning() {
    String leftContents = "No Change.";
    String rightContents = "With Change.";

    TextDiff expectedTextDiff =
        TextDiff.newBuilder()
            .addLeftChange(
                TextChange.newBuilder()
                    .setText("No")
                    .setType(ChangeType.DELETE)
                    .setEndIndex(2)
                    .build())
            .addLeftChange(
                TextChange.newBuilder()
                    .setText(" Change.")
                    .setType(ChangeType.NO_CHANGE)
                    .setStartIndex(2)
                    .setEndIndex(10)
                    .build())
            .addRightChange(
                TextChange.newBuilder()
                    .setText("With")
                    .setType(ChangeType.ADD)
                    .setEndIndex(4)
                    .build())
            .addRightChange(
                TextChange.newBuilder()
                    .setText(" Change.")
                    .setType(ChangeType.NO_CHANGE)
                    .setStartIndex(4)
                    .setEndIndex(12)
                    .build())
            .setLeftFileContents(leftContents)
            .setRightFileContents(rightContents)
            .build();
    assertEquals(expectedTextDiff, differencer.getTextDiff(leftContents, rightContents));
  }

  @Test
  public void testMixedChangesAtTheMiddle() {
    String leftContents = "With Change.";
    String rightContents = "With a Change.";

    TextDiff expectedTextDiff =
        TextDiff.newBuilder()
            .addLeftChange(
                TextChange.newBuilder()
                    .setText("With Change.")
                    .setType(ChangeType.NO_CHANGE)
                    .setEndIndex(12)
                    .build())
            .addRightChange(
                TextChange.newBuilder()
                    .setText("With ")
                    .setType(ChangeType.NO_CHANGE)
                    .setEndIndex(5)
                    .build())
            .addRightChange(
                TextChange.newBuilder()
                    .setText("a ")
                    .setType(ChangeType.ADD)
                    .setStartIndex(5)
                    .setEndIndex(7)
                    .build())
            .addRightChange(
                TextChange.newBuilder()
                    .setText("Change.")
                    .setType(ChangeType.NO_CHANGE)
                    .setStartIndex(7)
                    .setEndIndex(14)
                    .build())
            .setLeftFileContents(leftContents)
            .setRightFileContents(rightContents)
            .build();

    assertEquals(expectedTextDiff, differencer.getTextDiff(leftContents, rightContents));
  }

  @Test
  public void testMixedChangesAtTheEnd() {
    String leftContents = "Change at end.";
    String rightContents = "Change at end!";

    TextDiff expectedTextDiff =
        TextDiff.newBuilder()
            .addLeftChange(
                TextChange.newBuilder()
                    .setText("Change at end")
                    .setType(ChangeType.NO_CHANGE)
                    .setEndIndex(13)
                    .build())
            .addLeftChange(
                TextChange.newBuilder()
                    .setText(".")
                    .setType(ChangeType.DELETE)
                    .setStartIndex(13)
                    .setEndIndex(14)
                    .build())
            .addRightChange(
                TextChange.newBuilder()
                    .setText("Change at end")
                    .setType(ChangeType.NO_CHANGE)
                    .setEndIndex(13)
                    .build())
            .addRightChange(
                TextChange.newBuilder()
                    .setText("!")
                    .setType(ChangeType.ADD)
                    .setStartIndex(13)
                    .setEndIndex(14)
                    .build())
            .setLeftFileContents(leftContents)
            .setRightFileContents(rightContents)
            .build();

    assertEquals(expectedTextDiff, differencer.getTextDiff(leftContents, rightContents));
  }

  @Test
  public void testBuildFileChange() {
    String leftContents = readFile("BUILD_before.txt");
    String rightContents = readFile("BUILD_after.txt");
    TextDiff expectedTextDiff = readTextDiff("BUILD_diff_prototxt.txt");
    assertEquals(expectedTextDiff, differencer.getTextDiff(leftContents, rightContents));
  }

  @Singleton
  @Component(modules = CommonModule.class)
  interface TestComponent {
    FileUtils getFileUtils();
  }
}

