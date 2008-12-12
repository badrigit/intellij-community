package com.intellij.injected.editor;

import com.intellij.ide.DataManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.*;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ProperTextRange;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.util.text.CharArrayUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author Alexey
 */
public class DocumentWindowImpl extends UserDataHolderBase implements Disposable, DocumentWindow, DocumentEx {
  private static final Logger LOG = Logger.getInstance("com.intellij.openapi.editor.impl.injected.DocumentRangee");
  private final DocumentEx myDelegate;
  //sorted by startOffset
  private final RangeMarker[] myRelevantRangesInHostDocument;
  private final boolean myOneLine;
  private final String[] myPrefixes;
  private final String[] mySuffixes;
  private final int myPrefixLineCount;
  private final int mySuffixLineCount;

  public DocumentWindowImpl(@NotNull DocumentEx delegate, boolean oneLine, List<PsiLanguageInjectionHost.Shred> shreds) {
    myDelegate = delegate;
    myOneLine = oneLine;
    myPrefixes = new String[shreds.size()];
    mySuffixes = new String[shreds.size()];
    myRelevantRangesInHostDocument = new RangeMarker[shreds.size()];
    for (int i = 0; i < shreds.size(); i++) {
      PsiLanguageInjectionHost.Shred shred = shreds.get(i);
      myPrefixes[i] = shred.prefix;
      mySuffixes[i] = shred.suffix;
      myRelevantRangesInHostDocument[i] = shred.getHostRangeMarker();
      if (i != 0) {
        assert myRelevantRangesInHostDocument[i].getStartOffset() >= myRelevantRangesInHostDocument[i - 1].getStartOffset() : Arrays.asList(myRelevantRangesInHostDocument);
      }
    }
    myPrefixLineCount = Math.max(1, 1 + StringUtil.countNewLines(myPrefixes[0]));
    mySuffixLineCount = Math.max(1, 1 + StringUtil.countNewLines(mySuffixes[mySuffixes.length - 1]));
  }

  public int getLineCount() {
    return 1 + StringUtil.countNewLines(getText());
  }

  public int getLineStartOffset(int line) {
    assert line >= 0 : line;
    return new DocumentImpl(getText()).getLineStartOffset(line);
  }

  public int getLineEndOffset(int line) {
    if (line==0 && myPrefixes[0].length()==0) return getTextLength();
    return new DocumentImpl(getText()).getLineEndOffset(line);
  }

  public String getText() {
    StringBuilder text = new StringBuilder();
    String hostText = myDelegate.getText();
    for (int i = 0; i < myRelevantRangesInHostDocument.length; i++) {
      RangeMarker hostRange = myRelevantRangesInHostDocument[i];
      if (hostRange.isValid()) {
        text.append(myPrefixes[i]);
        text.append(hostText, hostRange.getStartOffset(), hostRange.getEndOffset());
        text.append(mySuffixes[i]);
      }
    }
    return text.toString();
  }

  public CharSequence getCharsSequence() {
    return getText();
  }

  public char[] getChars() {
    return CharArrayUtil.fromSequence(getText());
  }

  public int getTextLength() {
    int length = 0;
    for (int i = 0; i < myRelevantRangesInHostDocument.length; i++) {
      RangeMarker hostRange = myRelevantRangesInHostDocument[i];
      length += myPrefixes[i].length();
      length += hostRange.getEndOffset() - hostRange.getStartOffset();
      length += mySuffixes[i].length();
    }
    return length;
  }

  public int getLineNumber(int offset) {
    int lineNumber = 0;
    String hostText = myDelegate.getText();
    for (int i = 0; i < myRelevantRangesInHostDocument.length; i++) {
      String prefix = myPrefixes[i];
      String suffix = mySuffixes[i];
      lineNumber += StringUtil.getLineBreakCount(prefix.substring(0, Math.min(offset, prefix.length())));
      if (offset < prefix.length()) {
        return lineNumber;
      }
      offset -= prefix.length();
      
      RangeMarker currentRange = myRelevantRangesInHostDocument[i];
      int rangeLength = currentRange.getEndOffset() - currentRange.getStartOffset();
      String rangeText = hostText.substring(currentRange.getStartOffset(), currentRange.getEndOffset());

      lineNumber += StringUtil.getLineBreakCount(rangeText.substring(0, Math.min(offset, rangeLength)));
      if (offset < rangeLength) {
        return lineNumber;
      }
      offset -= rangeLength;

      lineNumber += StringUtil.getLineBreakCount(suffix.substring(0, Math.min(offset, suffix.length())));
      if (offset < suffix.length()) {
        return lineNumber;
      }

      offset -= suffix.length();
    }
    lineNumber = getLineCount() - 1;
    return lineNumber < 0 ? 0 : lineNumber;
  }

  public TextRange getHostRange(int hostOffset) {
    for (RangeMarker currentRange : myRelevantRangesInHostDocument) {
      TextRange textRange = InjectedLanguageUtil.toTextRange(currentRange);
      if (textRange.grown(1).contains(hostOffset)) return textRange;
    }                              
    return null;
  }

  public void insertString(final int offset, CharSequence s) {
    LOG.assertTrue(offset >= myPrefixes[0].length());
    LOG.assertTrue(offset <= getTextLength() - mySuffixes[mySuffixes.length-1].length());
    if (isOneLine()) {
      s = StringUtil.replace(s.toString(), "\n", "");
    }
    myDelegate.insertString(injectedToHost(offset), s);
  }

  public void deleteString(final int startOffset, final int endOffset) {
    assert intersectWithEditable(new TextRange(startOffset, startOffset)) != null;
    assert intersectWithEditable(new TextRange(endOffset, endOffset)) != null;

    List<TextRange> hostRangesToDelete = new ArrayList<TextRange>(myRelevantRangesInHostDocument.length);

    int offset = startOffset;
    int curRangeStart = 0;
    for (int i = 0; i < myRelevantRangesInHostDocument.length; i++) {
      curRangeStart += myPrefixes[i].length();
      if (offset < curRangeStart) offset = curRangeStart;
      if (offset >= endOffset) break;
      RangeMarker hostRange = myRelevantRangesInHostDocument[i];
      if (!hostRange.isValid()) continue;
      int hostRangeLength = hostRange.getEndOffset() - hostRange.getStartOffset();
      TextRange range = TextRange.from(curRangeStart, hostRangeLength);
      if (range.contains(offset)) {
        TextRange rangeToDelete = new TextRange(offset, Math.min(range.getEndOffset(), endOffset));
        hostRangesToDelete.add(rangeToDelete.shiftRight(hostRange.getStartOffset() - curRangeStart));
        offset = rangeToDelete.getEndOffset();
      }
      curRangeStart += hostRangeLength;
      curRangeStart += mySuffixes[i].length();
    }

    int delta = 0;
    for (TextRange hostRangeToDelete : hostRangesToDelete) {
      myDelegate.deleteString(hostRangeToDelete.getStartOffset() + delta, hostRangeToDelete.getEndOffset() + delta);
      delta -= hostRangeToDelete.getLength();
    }
  }

  public void replaceString(int startOffset, int endOffset, CharSequence s) {
    if (isOneLine()) {
      s = StringUtil.replace(s.toString(), "\n", "");
    }

    final CharSequence chars = getCharsSequence();
    CharSequence toDelete = chars.subSequence(startOffset, endOffset);

    int perfixLength = StringUtil.commonPrefixLength(s, toDelete);
    int suffixLength = StringUtil.commonSuffixLength(toDelete.subSequence(perfixLength, toDelete.length()), s.subSequence(perfixLength, s.length()));
    startOffset += perfixLength;
    endOffset -= suffixLength;
    s = s.subSequence(perfixLength, s.length() - suffixLength);
    
    deleteString(startOffset, endOffset);
    insertString(startOffset, s);
  }

  public boolean isWritable() {
    return myDelegate.isWritable();
  }

  public long getModificationStamp() {
    return myDelegate.getModificationStamp();
  }

  public void fireReadOnlyModificationAttempt() {
    myDelegate.fireReadOnlyModificationAttempt();
  }

  public void addDocumentListener(final DocumentListener listener) {
    myDelegate.addDocumentListener(listener);
  }

  public void addDocumentListener(DocumentListener listener, Disposable parentDisposable) {
    myDelegate.addDocumentListener(listener, parentDisposable);
  }

  public void removeDocumentListener(final DocumentListener listener) {
    myDelegate.removeDocumentListener(listener);
  }

  public RangeMarker createRangeMarker(final int startOffset, final int endOffset) {
    TextRange hostRange = injectedToHost(new ProperTextRange(startOffset, endOffset));
    RangeMarker hostMarker = myDelegate.createRangeMarker(hostRange);
    return new RangeMarkerWindow(this, (RangeMarkerEx)hostMarker);
  }

  public RangeMarker createRangeMarker(final int startOffset, final int endOffset, final boolean surviveOnExternalChange) {
    if (!surviveOnExternalChange) {
      return createRangeMarker(startOffset, endOffset);
    }
    TextRange hostRange = injectedToHost(new ProperTextRange(startOffset, endOffset));
    //todo persistent?
    return myDelegate.createRangeMarker(hostRange.getStartOffset(), hostRange.getEndOffset(), surviveOnExternalChange);
  }

  public MarkupModel getMarkupModel() {
    //noinspection deprecation
    return new MarkupModelWindow((MarkupModelEx)myDelegate.getMarkupModel(), this);
  }

  @NotNull
  public MarkupModel getMarkupModel(final Project project) {
    return new MarkupModelWindow((MarkupModelEx)myDelegate.getMarkupModel(project), this);
  }

  public void addPropertyChangeListener(final PropertyChangeListener listener) {
    myDelegate.addPropertyChangeListener(listener);
  }

  public void removePropertyChangeListener(final PropertyChangeListener listener) {
    myDelegate.removePropertyChangeListener(listener);
  }

  public void setReadOnly(final boolean isReadOnly) {
    myDelegate.setReadOnly(isReadOnly);
  }

  public RangeMarker createGuardedBlock(final int startOffset, final int endOffset) {
    TextRange hostRange = injectedToHost(new ProperTextRange(startOffset, endOffset));
    return myDelegate.createGuardedBlock(hostRange.getStartOffset(), hostRange.getEndOffset());
  }

  public void removeGuardedBlock(final RangeMarker block) {
    myDelegate.removeGuardedBlock(block);
  }

  public RangeMarker getOffsetGuard(final int offset) {
    return myDelegate.getOffsetGuard(injectedToHost(offset));
  }

  public RangeMarker getRangeGuard(final int startOffset, final int endOffset) {
    TextRange hostRange = injectedToHost(new ProperTextRange(startOffset, endOffset));

    return myDelegate.getRangeGuard(hostRange.getStartOffset(), hostRange.getEndOffset());
  }

  public void startGuardedBlockChecking() {
    myDelegate.startGuardedBlockChecking();
  }

  public void stopGuardedBlockChecking() {
    myDelegate.stopGuardedBlockChecking();
  }

  public void setCyclicBufferSize(final int bufferSize) {
    myDelegate.setCyclicBufferSize(bufferSize);
  }

  public void setText(CharSequence text) {
    LOG.assertTrue(text.toString().startsWith(myPrefixes[0]));
    LOG.assertTrue(text.toString().endsWith(mySuffixes[mySuffixes.length-1]));
    if (isOneLine()) {
      text = StringUtil.replace(text.toString(), "\n", "");
    }
    String[] changes = calculateMinEditSequence(text.toString());
    assert changes.length == myRelevantRangesInHostDocument.length;
    for (int i = 0; i < changes.length; i++) {
      String change = changes[i];
      RangeMarker hostRange = myRelevantRangesInHostDocument[i];
      myDelegate.replaceString(hostRange.getStartOffset(), hostRange.getEndOffset(), change);
    }
  }

  @NotNull
  public RangeMarker[] getHostRanges() {
    return myRelevantRangesInHostDocument;
  }

  public RangeMarker createRangeMarker(final TextRange textRange) {
    TextRange hostRange = injectedToHost(new ProperTextRange(textRange));
    RangeMarker hostMarker = myDelegate.createRangeMarker(hostRange);
    return new RangeMarkerWindow(this, (RangeMarkerEx)hostMarker);
  }

  public void stripTrailingSpaces(final boolean inChangedLinesOnly) {
    myDelegate.stripTrailingSpaces(inChangedLinesOnly);
  }

  public void setStripTrailingSpacesEnabled(final boolean isEnabled) {
    myDelegate.setStripTrailingSpacesEnabled(isEnabled);
  }

  public int getLineSeparatorLength(final int line) {
    return myDelegate.getLineSeparatorLength(injectedToHostLine(line));
  }

  public LineIterator createLineIterator() {
    return myDelegate.createLineIterator();
  }

  public void setModificationStamp(final long modificationStamp) {
    myDelegate.setModificationStamp(modificationStamp);
  }

  public void addEditReadOnlyListener(final EditReadOnlyListener listener) {
    myDelegate.addEditReadOnlyListener(listener);
  }

  public void removeEditReadOnlyListener(final EditReadOnlyListener listener) {
    myDelegate.removeEditReadOnlyListener(listener);
  }

  public void replaceText(final CharSequence chars, final long newModificationStamp) {
    setText(chars);
    myDelegate.setModificationStamp(newModificationStamp);
  }

  public int getListenersCount() {
    return myDelegate.getListenersCount();
  }

  public void suppressGuardedExceptions() {
    myDelegate.suppressGuardedExceptions();
  }

  public void unSuppressGuardedExceptions() {
    myDelegate.unSuppressGuardedExceptions();
  }

  public boolean isInEventsHandling() {
    return myDelegate.isInEventsHandling();
  }

  public void clearLineModificationFlags() {
  }

  public void removeRangeMarker(RangeMarkerEx rangeMarker) {
    myDelegate.removeRangeMarker(rangeMarker); //todo
  }

  public void addRangeMarker(RangeMarkerEx rangeMarker) {
    myDelegate.addRangeMarker(rangeMarker); //todo
  }

  public boolean isInBulkUpdate() {
    return false;
  }

  public void setInBulkUpdate(boolean value) {
  }

  @NotNull
  public DocumentEx getDelegate() {
    return myDelegate;
  }

  //todo use escaper?
  public int hostToInjected(int hostOffset) {
    if (hostOffset < myRelevantRangesInHostDocument[0].getStartOffset()) return myPrefixes[0].length();
    int offset = 0;
    for (int i = 0; i < myRelevantRangesInHostDocument.length; i++) {
      offset += myPrefixes[i].length();
      RangeMarker currentRange = myRelevantRangesInHostDocument[i];
      RangeMarker nextRange = i==myRelevantRangesInHostDocument.length-1 ? null : myRelevantRangesInHostDocument[i+1];
      if (nextRange == null || hostOffset < nextRange.getStartOffset()) {
        if (hostOffset >= currentRange.getEndOffset()) hostOffset = currentRange.getEndOffset();
        return offset + hostOffset - currentRange.getStartOffset();
      }
      offset += currentRange.getEndOffset() - currentRange.getStartOffset();
      offset += mySuffixes[i].length();
    }
    return getTextLength() - mySuffixes[mySuffixes.length-1].length();
  }

  public int injectedToHost(int offset) {
    int offsetInLeftFragment = injectedToHost(offset, true);
    int offsetInRightFragment = injectedToHost(offset, false);
    if (offsetInLeftFragment == offsetInRightFragment) return offsetInLeftFragment;

    // heuristics: return offset closest to caret
    Editor editor = PlatformDataKeys.EDITOR.getData(DataManager.getInstance().getDataContext());
    if (editor instanceof EditorWindow) editor = ((EditorWindow)editor).getDelegate();
    if (editor != null) {
      int caret = editor.getCaretModel().getOffset();
      return Math.abs(caret - offsetInLeftFragment) < Math.abs(caret - offsetInRightFragment) ? offsetInLeftFragment : offsetInRightFragment;
    }
    return offsetInLeftFragment;
  }

  private int injectedToHost(int offset, boolean preferLeftFragment) {
    if (offset < myPrefixes[0].length()) return myRelevantRangesInHostDocument[0].getStartOffset();
    int prevEnd = 0;
    for (int i = 0; i < myRelevantRangesInHostDocument.length; i++) {
      RangeMarker currentRange = myRelevantRangesInHostDocument[i];
      offset -= myPrefixes[i].length();
      int length = currentRange.getEndOffset() - currentRange.getStartOffset();
      if (offset < 0) {
        return preferLeftFragment ? prevEnd : currentRange.getStartOffset() - 1;
      }
      else if (offset == 0) {
        return preferLeftFragment && i != 0 ? prevEnd : currentRange.getStartOffset();
      }
      else if (offset < length || offset == length && preferLeftFragment) {
        return currentRange.getStartOffset() + offset;
      }
      offset -= length;
      offset -= mySuffixes[i].length();
      prevEnd = currentRange.getEndOffset();
    }
    return myRelevantRangesInHostDocument[myRelevantRangesInHostDocument.length-1].getEndOffset();
  }

  @NotNull
  public TextRange injectedToHost(@NotNull TextRange injected) {
    ProperTextRange.assertProperRange(injected);
    int start = injectedToHost(injected.getStartOffset(), false);
    int end = injectedToHost(injected.getEndOffset(), true);
    if (end < start) {
      end = injectedToHost(injected.getEndOffset(), false);
    }
    return new ProperTextRange(start, end);
  }

  public int injectedToHostLine(int line) {
    if (line < myPrefixLineCount) {
      return myDelegate.getLineNumber(myRelevantRangesInHostDocument[0].getStartOffset());
    }
    int lineCount = getLineCount();
    if (line > lineCount - mySuffixLineCount) {
      return lineCount;
    }
    int offset = getLineStartOffset(line);
    int hostOffset = injectedToHost(offset);

    return myDelegate.getLineNumber(hostOffset);
  }

  public boolean containsRange(int start, int end) {
    if (end - start > myRelevantRangesInHostDocument[0].getEndOffset() - myRelevantRangesInHostDocument[0].getStartOffset()) return false;
    for (RangeMarker hostRange : myRelevantRangesInHostDocument) {
      if (InjectedLanguageUtil.toTextRange(hostRange).contains(new ProperTextRange(start, end))) return true;
    }
    return false;
  }

  @Deprecated
  @Nullable
  public TextRange intersectWithEditable(@NotNull TextRange rangeToEdit) {
    int offset = 0;
    int startOffset = -1;
    int endOffset = -1;
    for (int i = 0; i < myRelevantRangesInHostDocument.length; i++) {
      RangeMarker hostRange = myRelevantRangesInHostDocument[i];
      offset += myPrefixes[i].length();
      int length = hostRange.getEndOffset() - hostRange.getStartOffset();
      TextRange intersection = new ProperTextRange(offset, offset + length).intersection(rangeToEdit);
      if (intersection != null) {
        if (startOffset == -1) {
          startOffset = intersection.getStartOffset();
        }
        endOffset = intersection.getEndOffset();
      }
      offset += length;
      offset += mySuffixes[i].length();
    }
    if (startOffset == -1) return null;
    return new ProperTextRange(startOffset, endOffset);
  }

  public boolean intersects(DocumentWindowImpl documentWindow) {
    int i = 0;
    int j = 0;
    while (i < myRelevantRangesInHostDocument.length && j < documentWindow.myRelevantRangesInHostDocument.length) {
      RangeMarker range = myRelevantRangesInHostDocument[i];
      RangeMarker otherRange = documentWindow.myRelevantRangesInHostDocument[j];
      if (InjectedLanguageUtil.toTextRange(range).intersects(InjectedLanguageUtil.toTextRange(otherRange))) return true;
      if (range.getEndOffset() > otherRange.getStartOffset()) i++;
      else if (range.getStartOffset() < otherRange.getEndOffset()) j++;
      else {
        i++;
        j++;
      }
    }
    return false;
  }

  // minimum sequence of text replacement operations for each host range
  // result[i] == null means no change
  // result[i] == "" means delete
  // result[i] == string means replace
  public String[] calculateMinEditSequence(String newText) {
    String[] result = new String[myRelevantRangesInHostDocument.length];
    String hostText = myDelegate.getText();
    calculateMinEditSequence(hostText, newText, result, 0, result.length - 1);
    for (int i = 0; i < result.length; i++) {
      String change = result[i];
      if (change == null) continue;
      assert change.startsWith(myPrefixes[i]) : change + " " + myPrefixes[i];
      assert change.endsWith(mySuffixes[i]) : change + " " + mySuffixes[i];
      result[i] = StringUtil.trimEnd(StringUtil.trimStart(change, myPrefixes[i]), mySuffixes[i]);
    }
    return result;
  }

  private String getRangeText(String hostText, int i) {
    return myPrefixes[i] + hostText.substring(myRelevantRangesInHostDocument[i].getStartOffset(), myRelevantRangesInHostDocument[i].getEndOffset()) + mySuffixes[i];
  }
  private void calculateMinEditSequence(String hostText, String newText, String[] result, int i, int j) {
    String rangeText1 = getRangeText(hostText, i);
    if (i == j) {
      result[i] = rangeText1.equals(newText) ? null : newText;
      return;
    }
    if (StringUtil.startsWith(newText, rangeText1)) {
      result[i] = null;  //no change
      calculateMinEditSequence(hostText, newText.substring(rangeText1.length()), result, i+1, j);
      return;
    }
    String rangeText2 = getRangeText(hostText, j);
    if (StringUtil.endsWith(newText, rangeText2)) {
      result[j] = null;  //no change
      calculateMinEditSequence(hostText, newText.substring(0, newText.length() - rangeText2.length()), result, i, j-1);
      return;
    }
    if (i+1 == j) {
      String separator = mySuffixes[i] + myPrefixes[j];
      if (separator.length() != 0) {
        int sep = newText.indexOf(separator);
        assert sep != -1;
        result[i] = newText.substring(0, sep + mySuffixes[i].length());
        result[j] = newText.substring(sep + mySuffixes[i].length() + myPrefixes[j].length(), newText.length());
        return;
      }
      String prefix = StringUtil.commonPrefix(rangeText1, newText);
      result[i] = prefix;
      result[j] = newText.substring(prefix.length());
      return;
    }
    String middleText = getRangeText(hostText, i + 1);
    int m = newText.indexOf(middleText);
    if (m != -1) {
      result[i] = newText.substring(0, m);
      result[i+1] = null;
      calculateMinEditSequence(hostText, newText.substring(m+middleText.length(), newText.length()), result, i+2, j);
      return;
    }
    middleText = getRangeText(hostText, j - 1);
    m = newText.lastIndexOf(middleText);
    if (m != -1) {
      result[j] = newText.substring(m+middleText.length());
      result[j-1] = null;
      calculateMinEditSequence(hostText, newText.substring(0, m), result, i, j-2);
      return;
    }
    result[i] = "";
    result[j] = "";
    calculateMinEditSequence(hostText, newText, result, i+1, j-1);
  }

  public boolean areRangesEqual(@NotNull DocumentWindow otherd) {
    DocumentWindowImpl window = (DocumentWindowImpl)otherd;
    if (myRelevantRangesInHostDocument.length != window.myRelevantRangesInHostDocument.length) return false;
    for (int i = 0; i < myRelevantRangesInHostDocument.length; i++) {
      if (!myPrefixes[i].equals(window.myPrefixes[i])) return false;
      if (!mySuffixes[i].equals(window.mySuffixes[i])) return false;

      RangeMarker hostRange = myRelevantRangesInHostDocument[i];
      RangeMarker other = window.myRelevantRangesInHostDocument[i];
      if (hostRange.getStartOffset() != other.getStartOffset()) return false;
      if (hostRange.getEndOffset() != other.getEndOffset()) return false;
    }
    return true;
  }

  public boolean isValid() {
    for (RangeMarker range : myRelevantRangesInHostDocument) {
      if (!range.isValid()) return false;
    }
    return true;
  }

  public boolean equals(Object o) {
    if (!(o instanceof DocumentWindowImpl)) return false;
    DocumentWindowImpl window = (DocumentWindowImpl)o;
    return myDelegate.equals(window.getDelegate()) && areRangesEqual(window);
  }

  public int hashCode() {
    return myRelevantRangesInHostDocument[0].getStartOffset();
  }

  public boolean isOneLine() {
    return myOneLine;
  }

  public void dispose() {
  }
}
