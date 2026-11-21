# Template store

Small internal service: partners upload "templates" (pickled objects) into `templates/`,
and `service/template_loader.py` loads them by name. `templates/welcome.pkl` is the demo
template. The exchange format was chosen years ago "because it preserves object graphs".

`secret/flag.txt` holds a masked test credential used by this exercise; `verify.py` is the
acceptance gate shipped with the exercise.
