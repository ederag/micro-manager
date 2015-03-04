/**
 * Micro-Manager "Live Replay"
 * 
 * This plugin will copy all images in the Micro-Manager circular buffer
 * into a viewer window.  The counter of the circular buffer is set to 0 (i.e.
 * images in the circular buffer will be destroyed.
 * 
 * This is useful when you see something interesting in live mode and want to 
 * save this data.
 * 
 * 
 * Nico Stuurman, 2009(?)
 * 
 * Updated June 2013 to use ImageProcssor Queue, so that images from multiple 
 * cameras are displayed correctly and so that images are all processed by
 * the default image processing queue.
 * 
 * 
 * Copyright University of California
 * 
 * LICENSE:      This file is distributed under the BSD license.
 *               License text is included with the source distribution.
 *
 *               This file is distributed in the hope that it will be useful,
 *               but WITHOUT ANY WARRANTY; without even the implied warranty
 *               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 *               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 *               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 *               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package org.micromanager.recall;

import java.util.concurrent.LinkedBlockingQueue;

import mmcorej.CMMCore;
import mmcorej.TaggedImage;
import org.json.JSONException;
import org.micromanager.MMPlugin;
import org.micromanager.ScriptInterface;
import org.micromanager.internal.MMStudio;
import org.micromanager.acquisition.internal.TaggedImageQueue;
import org.micromanager.data.Datastore;
import org.micromanager.data.DatastoreLockedException;
import org.micromanager.display.DisplayWindow;
import org.micromanager.internal.utils.MMTags;
import org.micromanager.internal.utils.MDUtils;
import org.micromanager.internal.utils.MMScriptException;




public class RecallPlugin implements MMPlugin {
   public static final String menuName = "Live Replay";
   public static final String tooltipDescription =
      "Recalls (live) images left over in the internal sequence buffer";
   private CMMCore core_;
   private MMStudio gui_;
   private MMStudio.DisplayImageRoutine displayImageRoutine_;
   // TODO: figure out how to assign this name to the viewer window
   private final String ACQ_NAME = "Live Replay";
   private int multiChannelCameraNrCh_;
   private Datastore store_;
   
  

   @Override
   public void setApp(ScriptInterface app) {
      gui_ = (MMStudio) app;                                        
      core_ = app.getMMCore(); 
      
      displayImageRoutine_ = new MMStudio.DisplayImageRoutine() {
         @Override
         public void show(final TaggedImage ti) {
            try {
               if (ti == TaggedImageQueue.POISON) {
                  store_ = null;
               } else {
                  store_.putImage(gui_.data().convertTaggedImage(ti));
               }
            } catch (MMScriptException e) {
               gui_.logError(e);
            } catch (DatastoreLockedException ex) {
               gui_.logError(ex);
            } catch (JSONException ex) {
               gui_.logError(ex);
            }
         }
      };
   }

   @Override
   public void dispose() {
      // nothing todo:
   }
   
   @Override
   public void show() {
      store_ = gui_.data().createRAMDatastore();
      DisplayWindow display = gui_.display().createDisplay(store_);
      gui_.display().track(store_);

      int remaining = core_.getRemainingImageCount();
      if (remaining < 1) {
         gui_.showMessage("There are no Images in the Micro-Manage buffer");
         return;
      }
      LinkedBlockingQueue<TaggedImage> imageQueue_ =
              new LinkedBlockingQueue<TaggedImage>();
      gui_.runDisplayThread(imageQueue_, displayImageRoutine_);
      
      multiChannelCameraNrCh_ = (int) core_.getNumberOfCameraChannels();
      String camera = core_.getCameraDevice();

      if (multiChannelCameraNrCh_ == 1) {
         int frameCounter = 0;
         try {
            for (int i = 0; i < remaining; i++) {
               TaggedImage tImg = core_.popNextTaggedImage();
               normalizeTags(tImg, frameCounter);
               frameCounter++;
               imageQueue_.put(tImg);
            }
            imageQueue_.put(TaggedImageQueue.POISON);
         } catch (Exception ex) {
            gui_.logError(ex, "Error in Live Replay");
         }
      } else if (multiChannelCameraNrCh_ > 1) {
         int[] frameCounters = new int[multiChannelCameraNrCh_];
         try {
            for (int i = 0; i < remaining; i++) {
               TaggedImage tImg = core_.popNextTaggedImage();
               
               if (tImg.tags.has(camera + "-CameraChannelName")) {
                  String channelName = tImg.tags.getString(camera + "-CameraChannelName");
                  tImg.tags.put("Channel", channelName);
                  int channelIndex = tImg.tags.getInt(camera + "-CameraChannelIndex");
                  tImg.tags.put("ChannelIndex", channelIndex);
                  normalizeTags(tImg, frameCounters[channelIndex]);
                  frameCounters[channelIndex]++;
                  imageQueue_.put(tImg);
               }
            }
            imageQueue_.put(TaggedImageQueue.POISON);
         } catch (Exception ex) {
            gui_.logError(ex, "Error in Live Replay Plugin");
         }
      }        
   }

   
   private void normalizeTags(TaggedImage ti, int frameIndex) {
      if (ti != TaggedImageQueue.POISON) {
         int channel = 0;
         try {
            if (ti.tags.has("Multi Camera-CameraChannelIndex")) {
               channel = ti.tags.getInt("Multi Camera-CameraChannelIndex");
            } else if (ti.tags.has("CameraChannelIndex")) {
               channel = ti.tags.getInt("CameraChannelIndex");
            } else if (ti.tags.has("ChannelIndex")) {
               channel = MDUtils.getChannelIndex(ti.tags);
            }
            ti.tags.put(MMTags.Image.CHANNEL_INDEX, channel);
            ti.tags.put(MMTags.Image.POS_INDEX, 0);
            ti.tags.put(MMTags.Image.SLICE_INDEX, 0);          
            ti.tags.put(MMTags.Image.FRAME, frameIndex);

         } catch (JSONException ex) {
            gui_.logError(ex);
         }
      }
   }
   
   public void configurationChanged() {
   }

   @Override
   public String getInfo () {
      return "Recalls live images remaining in internal buffer.  Set size of the buffer in options (under Tools menu)";
   }

   @Override
   public String getDescription() {
      return tooltipDescription;
   }
   
   @Override
   public String getVersion() {
      return "First version";
   }
   
   @Override
   public String getCopyright() {
      return "University of California, 2010-2015";
   }
}