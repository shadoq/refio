Create a complete single self-contained file `vfs_file_manager_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a virtual file manager.

The filesystem lives in localStorage as a hierarchy of folders and text files with correct path
handling: moving a folder carries its children, deleting a folder removes its whole subtree, and no
orphaned entries may remain after any operation. Navigation uses a folder tree panel plus clickable
breadcrumbs, and the current folder lists items with name, size and modified time.

Provide create, rename and delete for both files and folders, move and copy including into other
folders, and drag-and-drop import of files from the operating system using dataTransfer with an
upload button as fallback. Opening a text file shows an editor pane whose save writes changes back
into the filesystem, and a download action exports any file via Blob.

Add recursive search over file names and file contents with results that open the matching item, and
a storage-usage meter comparing the serialized filesystem size against the localStorage quota. Seed
a small demo tree on first run only. Every visible control must work; no fake simulation states.
Deliver the one file `vfs_file_manager_model_01.html`.
