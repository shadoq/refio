from src import main


def test_smoke():
    assert callable(main.main)
