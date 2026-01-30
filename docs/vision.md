# Task Manager (Startup page)
This page handles Task group creation. The distinction between task groups is 
their name and the script they're running. A simple shell UI has already been 
made for this page.
## Task Group Page
    This page can be accessed by clicking on any group in the task manager. 
    This page will contain the individual browser tasks that belong to a task group.
    There will be a + button in a similar position and style to the one in Task 
    Manager. This button will trigger a popup that asks for:
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
    This page also will contain a Start All and Stop All button for tasks, which can
    start or stop all tasks in the group. It will also have a change proxies button
    which allows the user to select a new group of proxies to use for the tasks.
    If a user selects a new proxy group with 10 proxies but there are 20 tasks, only
    the first 10 tasks will receive new proxies and the last 10 will remain unchanged.
### Task Container
    This represents a single task in the Task Group page. They will appear
    in rows (1 per row) and span the entire width of the available space in the UI.
    They will display the task name, status(custom per script), and real time logs 
    on the left and have a start, clone, and edit and delete button on the right 
    (in that order from left to right). The start button will transform to a stop 
    button when a task is running. The clone button makes an identical copy of 
    the task and appends it to the task list. The edit button will trigger a popup
    that looks similar to the task creation prompt that I described about, but it
    will instead only allow a single profile to be selected and will allow the
    user to enter a new proxy as a string. The can also set a custom status string.
    One key feature that we need to adjust our backend to support is preserving the
    browser userdata until a task is deleted. The user should be able to stop a browser
    and restart it later, picking up with the same cookies as before. This shouldn't be
    too complicated. It's as simple as keeping track of the userdata folder and starting
    chrome with that folder when the user clicks to start a task.
    I also just remembered that there needs to be an extra button for manually opening a
    headed browser session with all the same characteristics of the scripted task, just
    without the automation script running (proxy should still be present, should use the
    same fingerprint, etc.).

# Proxy Manager
This page allow the user to create proxy groups. The page will be formatted
the exact same way as Task Manager, but some features will be different.
One difference will be the popup that appears after the plus button. Obviously
the user will choose a name for the group. Instead of choosing a script, the user 
will choose a .txt file with one proxy per line. The user can also select which 
format his proxies are in (e.g. host:port:user:pass or user:pass@host:port).
Just like task manager, the groups can be deleted from the manager page.
The primary architectural difference between Task Manager and Proxy manager is
that the user can add proxies to a new Proxy Group upon initialization as
opposed to only being able to create tasks within the Task Group Page.
## Proxy Group Page
    This page is similar to the Task Group page. It presents the proxies in a
    similarly formatted list. The + button is still present and it will trigger
    a popup with the following options:
    1. The .txt file containing the proxies (1 per line).
    2. The format of the new proxies.
    3. An "Add Proxies" button that submits the form and appends the new
       proxies to the group.
### Proxy Container
    This represents a single proxy in a Proxy group and will be visible on the
    proxy group page. It will be formatted the same as a task container.
    The host of the proxy will act as a "name" which will be displayed on the
    far left of the container. The only other button will be on the far right,
    the delete button, which will remove a proxy from the group (no confirmation
    needed).
# Profile Manager
This page will, similarly to other managers, manage profile groups. The primary
difference is in the way groups are created. The popup will ask for the following
details in order to create a group:
1. The name of the profile group.
2. The .csv file containing the profiles.
## Profile Group Page
    This page is formatted similarly to the manager pages, with square-ish 
    containers for every Profile. There will be no + button to add more profiles.
    The only way to add profiles is by creating a new group and selecting a .csv.
### Profile Container
    This container will be shaped like an id card without a picture. It will
    display the profile name, email, and payment info, which are copied to 
    clipboard when clicked. You can also click on the profile container, which
    will trigger a popup that displays ALL information within the profile. It
    can all be clicked to copy to clipboard. There will even be an additional
    "Notes" text box where the user can leave notes on that specific profile.
    No other data besides the notes field can be edited. We'll need to go into
    more detail about the profile container when we actually start implementing
    it.

# Settings Page
This is where users can enter their AutoSolve AI API key. Eventually update checks
will be handled here too and any other global variable like paths to resources can
be configured. For now, we'll leave this page blank.



NOTES:
Certain buttons will have to be unclickable depending on the state of a task. For
example you can't edit a task while it's running. We'll discuss this kind of
thing right before the implementation of relevant components.
