Please make this change to `receipt.py`, and only this change:

Add a keyword parameter `tax_rate` to `format_receipt`, after `width`, with the default `0.0`. It is a fraction (`0.2` means 20 percent). When it is greater than zero, print one extra row between the rule and the total row, labelled `TAX 20%` (the percentage written without trailing zeros, for example `TAX 7.5%`) with the tax amount on the right, and make the total row include the tax. The tax is the subtotal times the rate, rounded half up to whole cents. A negative rate raises `ValueError`. With the default rate the output must stay exactly as it is today.

Leave everything else in the file as it is: the other functions, the existing lines of `format_receipt` and the docstrings. `python -m unittest discover -s tests` must pass; do not edit the tests.
