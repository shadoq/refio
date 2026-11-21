I am new to this code base (`shopkit`, about sixty Python files) and I am looking for one specific helper that I know exists but cannot find by grepping.

It takes two dates and a collection of dates on which the company is closed, and returns a plain integer: how many of the days in between are real working days. Saturdays and Sundays do not count and neither do the closed dates. The first date is counted if it qualifies, the last date is not.

Which function is it? Answer with the file path relative to the project root and the function name in the form `path/to/file.py::function_name`, followed by one sentence on why it is the right one and not a similar function elsewhere. This is a read-only question: do not modify any file.
