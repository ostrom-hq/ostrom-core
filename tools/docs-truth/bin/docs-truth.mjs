#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import {main} from "../cli.mjs"

process.exitCode = main(process.argv.slice(2), {out: (line) => console.log(line), err: (line) => console.error(line)})
