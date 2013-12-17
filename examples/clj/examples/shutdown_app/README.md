This simple standalone application is for testing the shutdown functionality
of Trapperkeeper. This is intended to be ran, and then killed with either
Ctrl-C or the kill command, and the services with shutdown hooks should be
called.

You should see instructions upon starting the application.

To run:
  lein test-external-shutdown
