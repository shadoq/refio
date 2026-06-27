This project hardcodes its API base URL over plain `http://`, which is insecure. Find the single
place where the base URL is defined (it is one named constant somewhere in `src/`) and change the
scheme to `https://`. Change only that constant; do not touch anything else.
