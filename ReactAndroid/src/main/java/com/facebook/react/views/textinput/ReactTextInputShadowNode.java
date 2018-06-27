/**
 * Copyright (c) 2015-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.views.textinput;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Layout;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.EditText;
import com.facebook.infer.annotation.Assertions;
import com.facebook.react.bridge.JSApplicationIllegalArgumentException;
import com.facebook.react.common.annotations.VisibleForTesting;
import com.facebook.react.uimanager.Spacing;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.UIViewOperationQueue;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.views.text.ReactBaseTextShadowNode;
import com.facebook.react.views.text.ReactTextUpdate;
import com.facebook.react.views.textinput.ReactTextInputLocalData;
import com.facebook.react.views.view.MeasureUtil;
import com.facebook.yoga.YogaMeasureFunction;
import com.facebook.yoga.YogaMeasureMode;
import com.facebook.yoga.YogaMeasureOutput;
import com.facebook.yoga.YogaNode;
import javax.annotation.Nullable;

// NOTE: This class is not much different from ReactTextInputShadowNode expect in the mechanism of
// setting theme context and performing measure
@VisibleForTesting
public class ReactTextInputShadowNode extends ReactBaseTextShadowNode
    implements YogaMeasureFunction {
  private static Drawable sDummyEditTextBackgroundDrawable = null;

  private int mMostRecentEventCount = UNSET;
  private @Nullable EditText mDummyEditText;
  private @Nullable ReactTextInputLocalData mLocalData;
  
  @VisibleForTesting public static final String PROP_TEXT = "text";

  // Represents the {@code text} property only, not possible nested content.
  private @Nullable String mText = null;

  public ReactTextInputShadowNode() {
      mTextBreakStrategy = (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) ?
              0 : Layout.BREAK_STRATEGY_SIMPLE;

      setMeasureFunction(this);
  }

  @Override
  public void setThemedContext(final ThemedReactContext themedContext) {
      super.setThemedContext(themedContext);

      // Inflating with layout using background as null because new EditText(context) call
      // can cause NullPointer Exception during a race scenario of UI thread performing EditText
      // creation with background at the same time
      //   BACKGROUND:
      // ---------------
      // SparseArray is not threadsafe and at the same time there is logic of gc() inside it
      // SparseArray is used by DrawableContainerState and new DrawableContainerState may get
      // created using the an existing constant state's drawable futures(this is SparseArray) by
      // cloning
      // The above is a recipe for a multi-threaded null pointer exception and it happens as below
      //    - Native module thread working on RTI shadow node creation and its lifecycle does a
      //      new EditText(reactThemedContext) which in turn results in background drawable to be
      //      set and finally invoking the SparseArray thread unsafe code
      //    - UI thread meanwhile could be used to create the display EditText of some other RTI
      //      at the same time leading up to the same SparseArray thread unsafe code
      //    - Null pointer exception happens when the gc() is invoked and simultaneously clone is
      //      being done for the same object giving rise to a partially gc-ed object. Something
      //      like an item from values array was removed and null-ed but the noOfItems flag is yet
      //      to be updated, so cloned object has one item less than specified in noOfItems flag
      //      and iteration can cause null pointer exception for the deleted index.
      //
      //   Solution:
      // ------------
      // Create EditText using layout inflater on Native module while specifying null for
      // background and create only once the background drawable by creating an EditText on UI
      // thread and caching its background drawable at shadowNode level. In this case measure only
      // needs to wait once for the Drawable creation
      //      Shortcomings: Ideally we would like to create the Drawable on the same Native module
      //                    thread but not able to access android.internal stylable ids to
      //                    approach this solution

      LayoutInflater sInflater =
              (LayoutInflater) themedContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
      EditText editText  =
              (EditText) sInflater.inflate(R.layout.dummy_edit_text, null, false);

      // creating the EditText theme background on UI thread async to prevent above mentioned race
      // scenario
      if (sDummyEditTextBackgroundDrawable == null) {
          themedContext.runOnUiQueueThread(new Runnable() {
              @Override
              public void run() {
                  sDummyEditTextBackgroundDrawable = new EditText(themedContext).getBackground();
              }
          });
      }

      mDummyEditText = editText;
  }

  @Override
  public long measure(YogaNode node, float width, YogaMeasureMode widthMode, float height,
          YogaMeasureMode heightMode) {
      // measure() should never be called before setThemedContext()
      EditText editText = Assertions.assertNotNull(mDummyEditText);

      if (mLocalData == null || sDummyEditTextBackgroundDrawable == null) {
          // No local data or edit text background drawable, no intrinsic size.
          return YogaMeasureOutput.make(0, 0);
      }

      // {@code EditText} has by default a border at the bottom of its view
      // called "underline". To have a native look and feel of the TextEdit
      // we have to preserve it at least by default.
      // The border (underline) has its padding set by the background image
      // provided by the system (which vary a lot among versions and vendors
      // of Android), and it cannot be changed.
      // So, we have to enforce it as a default padding.
      // Sharing the same background drawable is not working in measure and Edit Text features.
      // Hence, cloning.
      editText.setBackground(sDummyEditTextBackgroundDrawable.getConstantState().newDrawable());
      setDefaultPadding(Spacing.START, editText.getPaddingStart());
      setDefaultPadding(Spacing.TOP, editText.getPaddingTop());
      setDefaultPadding(Spacing.END, editText.getPaddingEnd());
      setDefaultPadding(Spacing.BOTTOM, editText.getPaddingBottom());

      // We must measure the EditText without paddings, so we have to reset them.
      editText.setPadding(0, 0, 0, 0);

      // This is needed to fix an android bug since 4.4.3 which will throw an NPE in measure,
      // setting the layoutParams fixes it: https://code.google.com/p/android/issues/detail?id=75877
      editText.setLayoutParams(
            new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

      mLocalData.apply(editText);

      editText.measure(
            MeasureUtil.getMeasureSpec(width, widthMode),
            MeasureUtil.getMeasureSpec(height, heightMode));

      return YogaMeasureOutput.make(editText.getMeasuredWidth(), editText.getMeasuredHeight());
  }

  @Override
  public boolean isVirtualAnchor() {
      return true;
  }

  @Override
  public boolean isYogaLeafNode() {
      return true;
  }

  @Override
  public void setLocalData(Object data) {
      Assertions.assertCondition(data instanceof ReactTextInputLocalData);
      mLocalData = (ReactTextInputLocalData) data;

      // Telling to Yoga that the node should be remeasured on next layout pass.
      dirty();

      // Note: We should NOT mark the node updated (by calling {@code markUpdated}) here
      // because the state remains the same.
  }

  @ReactProp(name = "mostRecentEventCount")
  public void setMostRecentEventCount(int mostRecentEventCount) {
      mMostRecentEventCount = mostRecentEventCount;
  }

  @ReactProp(name = PROP_TEXT)
  public void setText(@Nullable String text) {
      mText = text;
      markUpdated();
  }

  public @Nullable String getText() {
      return mText;
  }

  @Override
  public void setTextBreakStrategy(@Nullable String textBreakStrategy) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
          return;
      }

      if (textBreakStrategy == null || "simple".equals(textBreakStrategy)) {
          mTextBreakStrategy = Layout.BREAK_STRATEGY_SIMPLE;
      } else if ("highQuality".equals(textBreakStrategy)) {
          mTextBreakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY;
      } else if ("balanced".equals(textBreakStrategy)) {
          mTextBreakStrategy = Layout.BREAK_STRATEGY_BALANCED;
      } else {
          throw new JSApplicationIllegalArgumentException("Invalid textBreakStrategy: " + textBreakStrategy);
      }
  }

  @Override
  public void onCollectExtraUpdates(UIViewOperationQueue uiViewOperationQueue) {
    super.onCollectExtraUpdates(uiViewOperationQueue);

      if (mMostRecentEventCount != UNSET) {
          ReactTextUpdate reactTextUpdate =
              new ReactTextUpdate(
                      spannedFromShadowNode(this, getText()),
                      mMostRecentEventCount,
                      mContainsImages,
                      getPadding(Spacing.LEFT),
                      getPadding(Spacing.TOP),
                      getPadding(Spacing.RIGHT),
                      getPadding(Spacing.BOTTOM),
                      mTextAlign,
                      mTextBreakStrategy);
          uiViewOperationQueue.enqueueUpdateExtraData(getReactTag(), reactTextUpdate);
      }
  }

  @Override
  public void setPadding(int spacingType, float padding) {
      super.setPadding(spacingType, padding);
      markUpdated();
  }
}
