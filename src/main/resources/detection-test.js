window.__detectionResults = (() => {
    const results = {};

    // Test A: console.log
    try {
        const iterations = 10000;
        const start = performance.now();
        for (let i = 0; i < iterations; i++) console.log(i);
        results.consoleTiming = {
            elapsed: (performance.now() - start).toFixed(2) + 'ms'
        };
    } catch (e) { results.consoleTiming = { error: e.message }; }

    // Test B: Non-inlined builtin (Object.keys goes through full CEntry path)
    try {
        const iterations = 10000;
        const obj = {a:1, b:2, c:3};
        const start = performance.now();
        for (let i = 0; i < iterations; i++) Object.keys(obj);
        results.objectKeys = {
            elapsed: (performance.now() - start).toFixed(2) + 'ms'
        };
    } catch (e) { results.objectKeys = { error: e.message }; }

    // Test C: Another non-inlined builtin (JSON.stringify)
    try {
        const iterations = 10000;
        const obj = {a:1, b:2, c:3};
        const start = performance.now();
        for (let i = 0; i < iterations; i++) JSON.stringify(obj);
        results.jsonStringify = {
            elapsed: (performance.now() - start).toFixed(2) + 'ms'
        };
    } catch (e) { results.jsonStringify = { error: e.message }; }

    // Test D: Multiple iterations of console timing for noise check
    try {
        const runs = [];
        for (let r = 0; r < 5; r++) {
            const start = performance.now();
            for (let i = 0; i < 2000; i++) console.log(i);
            runs.push((performance.now() - start).toFixed(2));
        }
        results.consoleRuns = { runs: runs.map(r => r + 'ms') };
    } catch (e) { results.consoleRuns = { error: e.message }; }

    return results;
})();