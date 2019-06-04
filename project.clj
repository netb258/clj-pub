(defproject epub "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [me.raynes/fs "1.4.6"] ;; File tools.
                 [net.sf.cssbox/swingbox "1.1"] ;; Pure Java HTML rendering component.
                 [seesaw "1.5.0"]] ;; GUI library.
  :main ^:skip-aot epub.core
  :repl-options {:timeout 120000} ;; Set repl startup timeout to 1.2 min.
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
