// Augments Vite's ImportMetaEnv with the custom var this app injects. VITE_DATA_ROOT is
// the absolute path of benchmark/data on the dev machine (set in vite.config via
// `define`), used to build idea:// links that open an artifact file in IntelliJ.
interface ImportMetaEnv {
  readonly VITE_DATA_ROOT?: string;
}
