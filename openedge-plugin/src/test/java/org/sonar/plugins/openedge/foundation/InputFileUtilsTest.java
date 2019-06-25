/*
 * OpenEdge plugin for SonarQube
 * Copyright (c) 2015-2018 Riverside Software
 * contact AT riverside DASH software DOT fr
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.openedge.foundation;

import java.io.File;

import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultFileSystem;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.plugins.openedge.utils.TestProjectSensorContext;
import org.testng.Assert;
import org.testng.annotations.Test;

public class InputFileUtilsTest {

  @SuppressWarnings("deprecation")
  @Test
  public void testSp2k() throws Exception {
    SensorContextTester context = TestProjectSensorContext.createContext();
    InputFile f1 = context.fileSystem().inputFile(context.fileSystem().predicates().hasFilename("test1.p"));
    Assert.assertEquals(InputFileUtils.getFile(f1), f1.file());
    Assert.assertEquals(InputFileUtils.getRelativePath(f1, context.fileSystem()), TestProjectSensorContext.FILE1);
  }

  @Test
  public void testDifferentRoot() throws Exception {
    // In SonarLint, 'src' directory can be a linked folder pointing at another drive
    SensorContextTester context = TestProjectSensorContext.createContext();
    InputFile f1 = context.fileSystem().inputFile(context.fileSystem().predicates().hasFilename("test1.p"));
    Assert.assertEquals(InputFileUtils.getRelativePath(f1, new DefaultFileSystem(new File("Z:\\src"))), TestProjectSensorContext.FILE1);
  }

}
