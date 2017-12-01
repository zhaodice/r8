// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.apiusagesample;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.origin.ArchiveEntryOrigin;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.origin.PathOrigin;
import com.android.tools.r8.origin.TextRangeOrigin;
import java.nio.file.Files;
import java.nio.file.Path;

class D8DiagnosticsHandler implements DiagnosticsHandler {

  public D8DiagnosticsHandler() {
  }

  public static Origin getOrigin(Path root, Path entry) {
    if (Files.isRegularFile(root)) {
      return new ArchiveEntryOrigin(entry.toString(), new PathOrigin(root));
    } else {
      return new PathOrigin(root.resolve(entry.toString()));
    }
  }

  @Override
  public void error(Diagnostic error) {
    convertToMessage(error);
  }

  @Override
  public void warning(Diagnostic warning) {
    convertToMessage(warning);
  }

  @Override
  public void info(Diagnostic info) {
    convertToMessage(info);
  }

  protected void convertToMessage(Diagnostic diagnostic) {
    String textMessage = diagnostic.getDiagnosticMessage();

    Origin origin = diagnostic.getOrigin();
    String position;
    if (origin instanceof TextRangeOrigin && origin.parent() instanceof PathOrigin) {
      TextRangeOrigin textRange = (TextRangeOrigin) origin;
      position = ((PathOrigin) origin.parent()).getPath().toFile() + ": "
          + textRange.getStart().getLine() + "," + textRange.getStart().getColumn()
          + " - " + textRange.getEnd().getLine() + "," + textRange.getEnd().getColumn();
    } else if (origin.parent() instanceof PathOrigin) {
      position = ((PathOrigin) origin.parent()).getPath().toFile().toString();
    } else {
      position = "UNKNOWN";
      if (origin != Origin.unknown()) {
        textMessage = origin.toString() + ": " + textMessage;
      }
    }

    System.out.println(position + ": " + textMessage);
  }
}
