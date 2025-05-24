(ns epub.core
  (:import [java.util.zip ZipFile]
           [javafx.embed.swing JFXPanel]
           [javafx.application Platform]
           [javafx.scene Scene]
           [javafx.scene.web WebView])
  (:require [me.raynes.fs.compression :as compress]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as s])
  (:use [seesaw.core :only [native! config! show! scrollable frame left-right-split vertical-panel tabbed-panel button action menu menubar menu-item]]
        [seesaw.chooser]
        [clojure.java.shell :only [sh]]
        [clojure.xml :only [parse]])
  (:gen-class))

(def temp-dir "tmp")
(def epub-file (atom ""))
(def sys-sep (java.io.File/separator))

(defn get-pwd
  "Returns the current working directory as a string."
  []
  (-> (java.io.File. ".") .getAbsolutePath))

(defn get-book-name
  [book-file-path]
  (-> book-file-path
       (s/split (re-pattern (java.util.regex.Pattern/quote sys-sep)))
       (last)
       (s/split #"\.")
       (first)))

(defn get-book-files [epub-file-path]
  (->> (str temp-dir "/" (get-book-name epub-file-path)) (io/file) (file-seq)))

(defn get-book-dir [epub-file-path]
  (let [toc-file (->> (get-book-files epub-file-path) (filter #(s/ends-with? (str %) ".ncx")) (first))
        toc-dir-path (.getParent toc-file)]
    toc-dir-path))

;; ----------------------------------------------------------------------------------------------------------------
;; ------------------------------------------ Table of contents handling ------------------------------------------
;; ----------------------------------------------------------------------------------------------------------------

;; A seq of parsed <navPoint> tags represents the table of contents of the book.
(defn nav-points [toc-parsed]
  (->> toc-parsed
       :content ;; Get all tags in the toc XML file.
       (filter #(= :navMap (:tag %))) ;; Find only the <navMap> tag. Inside it there should be several <navPoint> tags.
       (first) ;; Filter wraps the result in a seq, so we need to unwrap it here with first.
       :content)) ;; Get the <navPoint> tags in a vector.

; We want to get the file-path and the chapter names from the navPoints: {:path "xhtml/chapter_001.html", :name "Chapter 1"}
(defn nav-point->map [nav-point]
  (hash-map
   ;; To get the file paths we need to seek out a <content> (inside <navPoint>) and get it's src attribute.
   :path (->>
          nav-point
          :content
          (filter #(= :content (:tag %)))
          (first)
          :attrs
          :src)
   ;; To get the chapter names we need to seek out a <navLabel> (inside <navPoint>), then get the <text> tag inside <navLabel>.
   :name (->>
          nav-point
          :content
          (filter #(= :navLabel (:tag %)))
          (first)
          :content
          (filter #(= :text (:tag %)))
          (first)
          :content
          (first))))

(defn nested-nav-points?
  "navPoint tags can be nested inside other navPoint tags.
  This function returns true if that is the case."
  [nav-point]
  (not
   (empty?
    (filter #(= :navPoint (:tag %)) (:content nav-point)))))

;; We want the chapters as a seq of maps like this '({:path "xhtml/chapter_001.html", :name "Chapter 1"} ...)
(defn nav-seq
  ([nav-points] (nav-seq nav-points '()))
  ([nav-points result]
   (cond
     (empty? nav-points) result
     (nested-nav-points? (last nav-points)) (nav-seq
                                              (butlast nav-points)
                                              (concat 
                                               (list (nav-point->map (last nav-points)))
                                               (conj result (nav-seq (:content (last nav-points)) '()))))
     :else (nav-seq
            (butlast nav-points)
            (conj result (nav-point->map (last nav-points)))))))

(defn get-chapters [book-files]
  (when (not= "" @epub-file)
    (let [toc-file (->> book-files (filter #(s/ends-with? (str %) ".ncx")) (first))
          toc-parsed (parse toc-file)]
      (filter
        #(not (and (nil? (:name %)) (nil? (:path %))))
        (flatten (nav-seq (nav-points toc-parsed)))))))

(defn get-full-path [chapter-path]
  (str "file:///" (get-pwd) "/" (get-book-dir @epub-file) "/" chapter-path))

;; ----------------------------------------------------------------------------------------------------------------
;; --------------------------------------------- content.opf handling ---------------------------------------------
;; ----------------------------------------------------------------------------------------------------------------

;; At this point we should have parsed the table of contents file.
;; Now we are going to parse contents.opf, which should contain a description of every resource in the book (images, html files xml files, everything).
;; We are doing this because the table of contents file can be (and often is) malformed.

(defn all-book-files [opf-parsed]
  (->> opf-parsed
       :content
       (filter #(= :manifest (:tag %))) ;; Find only the <manifest> tag. Inside it there should be several <item> tags.
       (first) ;; Filter wraps the result in a seq, so we need to unwrap it here with first.
       :content))

;; The spine should contain the files of the book in the order that they should appear.
;; Note, that they probably won't be well named, like in the table of content file.
(defn spine-ids [opf-parsed]
  (->> opf-parsed
       :content
       (filter #(= :spine (:tag %)))
       (first)
       :content
       (map #(->> % :attrs :idref)))) ;; We only need the IDs of the book's resources.

(defn spine-hrefs [all-book-files spine-ids]
  (->> all-book-files
      (filter #(.contains spine-ids (->> % :attrs :id)))
      (map #(->> % :attrs :href))))

;; We need the spine conents in a seq of maps like this:
;; ({:path "index_split_000.html", :name "Part 1"} {:path "index_split_001.html", :name "Part 2"} ...)
(defn spine [spine-hrefs]
  (map-indexed
    (fn [idx itm] {:path itm :name (str "Part " (inc idx))})
    spine-hrefs))

(defn get-spine [book-files]
  (when (not= "" @epub-file)
   (let [opf-file (->> book-files (filter #(s/ends-with? (str %) ".opf")) (first))
         opf-parsed (parse opf-file)
         book-files (all-book-files opf-parsed)
         sp-ids (spine-ids opf-parsed)]
     (->> (spine-hrefs book-files sp-ids) (spine)))))

;; NOTE: This function is not needed anymore, as SwingBox has been replaced with JavaFX.
;; It seems SwingBox has some problems with xhtml files, so we need to rename them to html files.
;; (defn rename-xhtml [chapter]
;;   (if (s/includes? (:path chapter) ".xhtml")
;;     (let [file-head (first (s/split (:path chapter) #"\."))
;;           new-path (str file-head ".html")
;;           new-path-full (str (get-pwd) sys-sep (get-book-dir @epub-file) sys-sep new-path)
;;           old-path-full (str (get-pwd) sys-sep (get-book-dir @epub-file) sys-sep file-head ".xhtml")]
;;       (when (not (fs/exists? new-path-full))
;;         (fs/rename
;;           old-path-full
;;           new-path-full))
;;       (assoc-in chapter [:path] new-path))
;;     chapter))

;; ----------------------------------------------------------------------------------------------------------------
;; ------------------------------------------------ GUI rendering -------------------------------------------------
;; ----------------------------------------------------------------------------------------------------------------

;; (native!)

;; NOTE: Need to keep the JavaFX (WebView.) in an atom, otherwise it complains about improper state.
(def html-pane (atom nil))

(defn create-javafx-webview-panel!
  "Creates a JavaFX WebView component with zoom functionality using Ctrl + mouse wheel."
  []
  (let [jfx-panel (JFXPanel.)] ; Initializes JavaFX toolkit
    (Platform/runLater
      (fn []
        (let [webview (WebView.)]
          (reset! html-pane webview) ; Store WebView in an atom for later use.
          ;; Add zoom functionality using Ctrl + mouse scroll
          (.setOnScroll webview
            (reify javafx.event.EventHandler
              (handle [_ event]
                (when (.isControlDown event) ; Correct way to check if Ctrl key is pressed
                  (let [deltaY (.getDeltaY event)
                        current-zoom (.getZoom webview)]
                    (.setZoom webview (if (> deltaY 0) (+ current-zoom 0.1) (max 0.1 (- current-zoom 0.1)))))))))
          (.setScene jfx-panel (Scene. webview)))))
    jfx-panel))

(defn make-nav-buttons [chapters]
  (when (not= "" @epub-file)
    (map
      (fn [chapter]
        (button
          :text (:name chapter)
          :tip (:name chapter)
          ;; NOTE: I need to handle the html-pane inside a JavaFX Platform/runLater, othersize the JavaFX web component complains about being in the wrong thread.
          :listen [:action (fn [event]
                             (Platform/runLater
                               (fn []
                                 (let [engine (.getEngine @html-pane)
                                       load-worker (.getLoadWorker engine)]
                                   (.load engine (-> (java.net.URL. (get-full-path (:path chapter))) .toExternalForm))
                                   ;; We need to add a bit of JavaScript to ensure the text displays correctly even if Zoomed.
                                   ;; Ensure JavaScript executes AFTER the page and the DOM loads.
                                   (.addListener (.stateProperty load-worker)
                                                 (reify javafx.beans.value.ChangeListener
                                                   (changed [_ _ _ new-state]
                                                     (when (= new-state javafx.concurrent.Worker$State/SUCCEEDED)
                                                       (.executeScript engine " var elements = document.querySelectorAll('p, div, span, h1, h2, h3, h4, h5, h6');
                                                                                for (var i = 0; i < elements.length; i++) {
                                                                                  elements[i].style.marginLeft = '0.3em';
                                                                                  elements[i].style.marginRight = '1em';
                                                                                  elements[i].style.textIndent = '0';
                                                                                  elements[i].style.textAlign = 'justify';}")))))))))]))
      chapters)))

(defn make-gui []
  (left-right-split
   (tabbed-panel
    :tabs
    [{:title "Table of content" :content (scrollable (vertical-panel :items (make-nav-buttons (get-chapters (get-book-files @epub-file)))))}
     {:title "Spine" :content (scrollable (vertical-panel :items (make-nav-buttons (get-spine (get-book-files @epub-file)))))}])
   ;; NOTE: About this IF. I don't want to create any JavaFX components in my main GUI unless they are actually needed. This helps a lot with lein uberjar.
   (if (not= "" @epub-file) (scrollable (create-javafx-webview-panel!)) nil)))

(declare main-window)

(def choose-book-action
  (action :name "Load file" :key "menu O"
          :handler 
          (fn [e]
            (if-let [f (choose-file :dir "./books")]
              (do
                (swap! epub-file (fn [x] (str f)))
                (when (not (fs/exists? (str temp-dir "/" (get-book-name @epub-file))))
                 (compress/unzip @epub-file (str temp-dir "/" (get-book-name @epub-file))))
                (config! main-window :content (make-gui))
                (let [title-page (first (get-chapters (get-book-files @epub-file)))]
                  ;; NOTE: I need to handle the html-pane inside a JavaFX Platform/runLater, othersize the JavaFX web component complains about being in the wrong thread.
                  (Platform/runLater #(.load (.getEngine @html-pane) (-> (java.net.URL. (get-full-path (:path title-page))) .toExternalForm)))))))))

(defn make-menu-bar []
  (menubar :items
           [(menu :text "File"
                  :items [choose-book-action
                          (menu-item :text "Books directory"
                                     :listen [:action
                                              (fn [event] (sh "cmd" "/C" "explorer" (str "\"" "books" "\"")))])
                          (menu-item :text "Books data"
                                     :listen [:action
                                              (fn [event] (sh "cmd" "/C" "explorer" (str "\"" "tmp" "\"")))])
                          (menu-item :text "Exit" :listen [:action (fn [event] (System/exit 0))])])]))

(defn make-main-window [child-widgets]
  (frame :title "EPUB Reader"
         :icon (io/file "icon/icon.png")
         :width 900
         :height 850
         :menubar (make-menu-bar)
         :content child-widgets
         :on-close :exit))

(def main-window
  (make-main-window (make-gui)))

(defn -main
  [& args]
  (show! main-window)
  (println "Done!"))
