/********************************************************************************
 * Copyright (c) 2003-2015 John Green
 * Copyright (c) 2015-2020 Riverside Software
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU Lesser General Public License v3.0
 * which is available at https://www.gnu.org/licenses/lgpl-3.0.txt
 *
 * SPDX-License-Identifier: EPL-2.0 OR LGPL-3.0
 ********************************************************************************/
package org.prorefactor.core.nodetypes;

import javax.annotation.Nonnull;

import org.prorefactor.core.IConstants;
import org.prorefactor.core.JPNode;
import org.prorefactor.core.ProToken;
import org.prorefactor.proparse.SymbolScope.FieldType;
import org.prorefactor.treeparser.ContextQualifier;

import com.google.common.base.Strings;

public class RecordNameNode extends JPNode {
  private String sortAccess = "";
  private boolean wholeIndex;
  private String searchIndexName = "";
  private ContextQualifier qualifier;

  public RecordNameNode(ProToken t, JPNode parent, int num, boolean hasChildren) {
    super(t, parent, num, hasChildren);
  }

  public void setContextQualifier(ContextQualifier qualifier) {
    this.qualifier = qualifier;
  }

  public ContextQualifier getQualifier() {
    return qualifier;
  }

  public String getSortAccess() {
    return sortAccess;
  }

  public boolean isWholeIndex() {
    return wholeIndex;
  }

  public String getSearchIndexName() {
    return searchIndexName;
  }

  public void setSortAccess(String str) {
    if (Strings.isNullOrEmpty(str))
      return;
    sortAccess = sortAccess + (sortAccess.isEmpty() ? "" : ',') + str;
  }

  public void setWholeIndex(boolean wholeIndex) {
    this.wholeIndex = wholeIndex;
  }

  public void setSearchIndexName(String indexName) {
    this.searchIndexName = indexName;
  }

  /** Set the 'store type' attribute on a RECORD_NAME node. */
  public void setStoreType(@Nonnull FieldType tabletype) {
    switch (tabletype) {
      case DBTABLE:
        attrSet(IConstants.STORETYPE, IConstants.ST_DBTABLE);
        break;
      case TTABLE:
        attrSet(IConstants.STORETYPE, IConstants.ST_TTABLE);
        break;
      case WTABLE:
        attrSet(IConstants.STORETYPE, IConstants.ST_WTABLE);
        break;
      case VARIABLE:
        // Never happens
        break;
    }
  }

}
