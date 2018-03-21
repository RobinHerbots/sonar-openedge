/*******************************************************************************
 * Original work Copyright (c) 2003-2015 John Green
 * Modified work Copyright (c) 2015-2018 Riverside Software
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    John Green - initial API and implementation and/or initial documentation
 *    Gilles Querret - Almost anything written after 2015
 *******************************************************************************/ 
package org.prorefactor.refactor.settings;

import java.util.List;

import org.prorefactor.refactor.settings.ProparseSettings.OperatingSystem;

public interface IProparseSettings {

  boolean isMultiParse();
  boolean getProparseDirectives();
  boolean useBackslashAsEscape();
  String getPropath();
  List<String> getPropathAsList();

  boolean getBatchMode();
  OperatingSystem getOpSys();
  String getProversion();
  String getWindowSystem();
  String getProcessArchitecture();
}
