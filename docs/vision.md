# Task Manager (Startup page)
This page handles Task group creation. The distinction between task groups is their name and the script they're running. A simple shell UI has already been made for this page.
## Task Group Page
    This page can be accessed by clicking on any group in the task manager. 
    This page will contain the individual browser tasks that belong to a task group.
    There will be a + button in a similar position and style to the one in Task Manager.
    This button will trigger a popup that asks for:
    1. The profiles to create tasks for. This will be a dropdown menu where you 
        first select a profile group, then you can either select all profiles
        from that group or check off multiple profiles to use. Profiles will
        displayed as <Profile Name> (<Profile Email Address>).
    2. The proxy group to use. If a user selects a group with 50 proxies but
        only wants to create 10 profiles, the first 10 proxies from the list
        will be assigned. Proxies groups do not "dispense" proxies as they
        don't remove the proxies that are used. They stay the same until
        the user edits the group in the proxies tab.
    3. The user can check a box to indicate whether or not to warm the
        session with activiy. Off by default.
    The create button will be at the bottom of the popup.
### Task Container
    This represents a single task in the Task Group page. They will appear
    in rows (1 per row) and span the entire width of the available space in the UI.
    They will display the task name, status(custom per script), and real time logs 
    on the left and have a start, clone, and edit button on the right 
    (in that order from left to right). The start button will transform to a close 
    button when a task is running. The clone button makes an identical copy of 
    the task and appends it to the task list. The edit button will trigger a popup
    that looks similar to the task creation prompt that I described about, but it
    will instead only allow a single profile to be selected and will allow the
    user to enter a new proxy as a string. The can also set a custom status string.

to be continued...