define( [
    'dojo/_base/declare',
    'dojo/mouse',
    'dojo/query',
    'dojo/dom',
    'dojo/dom-style',
    'dojo/dom-class',
    'dojo/_base/array',
    'dojo/on',
    'dijit/Menu',
    'dijit/MenuItem',
    'JBrowse/View/Track/Sequence',
    'WebApollo/JSONUtils',
    'WebApollo/Permission',
    'dojo/request/xhr',
    'dojox/widget/Standby',
     ],
function(
    declare,
    mouse,query,
    dom,
    domStyle,
    domClass,
    array,
    on,
    Menu,
    MenuItem,
    Sequence,
    JSONUtils,
    Permission,
    xhr,
    Standby ) {

return declare( [Sequence],
{
    /**
     * Track to display the underlying reference sequence, when zoomed in
     * far enough.
     */
    constructor: function( args ) {
        var track = this;
        this.browser.getPlugin( 'WebApollo', dojo.hitch( this, function(p) {
            this.webapollo = p;
        }));
        this.context_path = "..";
        this.annotationPrefix = "Annotations-";
        this.loadTranslationTable();
    },

    /*
     *  sequence alteration UPDATE command received by a ChangeNotificationListener
     *  currently handled as if receiving DELETE followed by ADD command
     */
    annotationsUpdatedNotification: function(annots)  {
        this.annotationsDeletedNotification(annots);
        this.annotationAddedNotification(annots);
    },
    
    storedFeatureCount: function(start, end)  {
        var track = this;
        if (start == undefined) {
            start = track.refSeq.start;
        }
        if (end == undefined) {
            end = track.refSeq.end;
        }
        var count = 0;
        track.store.getFeatures({ ref: track.refSeq.name, start: start, end: end}, function() { count++; });
        
        return count;
    }, 
    fillBlock: function(args) {
        var thisB=this;
        var supermethod = this.getInherited(arguments);
        var finishCallback=args.finishCallback;
        args.finishCallback=function() {
            finishCallback();
            // Add right-click menu
            if(!this._menu) {
                this._menu = new Menu({
                    targetNodeIds:['track_DNA'],
                    selector: "td.base"
                });
                this._menu.addChild(new MenuItem({
                    label: "Create insertion",
                    iconClass: "dijitIconNewTask",
                    onClick: function(evt){
                        var node = this.getParent().currentTarget;
                        alert("Insertion for node ", node);
                    }
                }));
                this._menu.addChild(new MenuItem({
                    label: "Create deletion",
                    iconClass: "dijitIconDelete",
                    onClick: function(evt){
                        var node = this.getParent().currentTarget;
                        alert("Deletion for node ", node);
                    }
                }));
            }

            // Add mouseover highlight
            var nl=query('.base',args.block.domNode);
            nl.style("backgroundColor","#E0E0E0")
            nl.on(mouse.enter,function(evt) {
              domStyle.set(evt.target,"backgroundColor","orange");
            });
            nl.on(mouse.leave,function(evt) {
              domStyle.set(evt.target,"backgroundColor","#E0E0E0");
            });

            // Add colorCdsByFrame
            if (thisB.browser.cookie("colorCdsByFrame")=="1") {
                query(".translatedSequence").addClass("colorCds");
            }
            else {
                query(".translatedSequence .colorCds").removeClass("colorCds");
            }
        };
        supermethod.call(this,args);
    },

    createAddSequenceAlterationPanel: function(type, gcoord) {
        var track = this;
        var content = dojo.create("div");
        var charWidth = 15;
        if (type == "deletion") {
            var deleteDiv = dojo.create("div", { }, content);
            var deleteLabel = dojo.create("label", { innerHTML: "Length", className: "sequence_alteration_input_label" }, deleteDiv);
            var deleteField = dojo.create("input", { type: "text", size: 10, className: "sequence_alteration_input_field" }, deleteDiv);

            $(deleteField).keydown(function(e) {
                var unicode = e.charCode || e.keyCode;
                var isBackspace = (unicode == 8);  // 8 = BACKSPACE
                if (unicode == 13) {  // 13 = ENTER/RETURN
                    addSequenceAlteration();
                }
                else {
                    var newchar = String.fromCharCode(unicode);
                    // only allow numeric chars and backspace
                    if (! (newchar.match(/[0-9]/) || isBackspace))  {  
                        return false; 
                    }
                }
            });
        }
        else {
            var plusDiv = dojo.create("div", { }, content);
            var minusDiv = dojo.create("div", { }, content);
            var plusLabel = dojo.create("label", { innerHTML: "+ strand", className: "sequence_alteration_input_label" }, plusDiv);
            var plusField = dojo.create("input", { type: "text", size: charWidth, className: "sequence_alteration_input_field" }, plusDiv);
            var minusLabel = dojo.create("label", { innerHTML: "- strand", className: "sequence_alteration_input_label" }, minusDiv);
            var minusField = dojo.create("input", { type: "text", size: charWidth, className: "sequence_alteration_input_field" }, minusDiv);
            $(plusField).keydown(function(e) {
                var unicode = e.charCode || e.keyCode;
                // ignoring delete key, doesn't do anything in input elements?
                var isBackspace = (unicode == 8);  // 8 = BACKSPACE
                if (unicode == 13) {  // 13 = ENTER/RETURN
                    addSequenceAlteration();
                }
                else {
                    var curval = e.srcElement.value;
                    var newchar = String.fromCharCode(unicode);
                    // only allow acgtnACGTN and backspace
                    //    (and acgtn are transformed to uppercase in CSS)
                    if (newchar.match(/[acgtnACGTN]/) || isBackspace)  {  
                        // can't synchronize scroll position of two input elements, 
                        // see http://stackoverflow.com/questions/10197194/keep-text-input-scrolling-synchronized
                        // but, if scrolling triggered (or potentially triggered), can hide other strand input element
                        // scrolling only triggered when length of input text exceeds character size of input element
                        if (isBackspace)  {
                            minusField.value = track.complement(curval.substring(0,curval.length-1));  
                        }
                        else {
                            minusField.value = track.complement(curval + newchar);  
                        }
                        if (curval.length > charWidth) {
                            $(minusDiv).hide();
                        }
                        else {
                            $(minusDiv).show();  // make sure is showing to bring back from a hide
                        }
                    }
                    else { return false; }  // prevents entering any chars other than ACGTN and backspace
                }
            });

            $(minusField).keydown(function(e) {
                var unicode = e.charCode || e.keyCode;
                // ignoring delete key, doesn't do anything in input elements?
                var isBackspace = (unicode == 8);  // 8 = BACKSPACE
                if (unicode == 13) {  // 13 = ENTER
                    addSequenceAlteration();
                }
                else {
                    var curval = e.srcElement.value;
                    var newchar = String.fromCharCode(unicode);
                    // only allow acgtnACGTN and backspace
                    //    (and acgtn are transformed to uppercase in CSS)
                    if (newchar.match(/[acgtnACGTN]/) || isBackspace)  {  
                        // can't synchronize scroll position of two input elements, 
                        // see http://stackoverflow.com/questions/10197194/keep-text-input-scrolling-synchronized
                        // but, if scrolling triggered (or potentially triggered), can hide other strand input element
                        // scrolling only triggered when length of input text exceeds character size of input element
                        if (isBackspace)  {
                            plusField.value = track.complement(curval.substring(0,curval.length-1));  
                        }
                        else {
                            plusField.value = track.complement(curval + newchar);  
                        }
                        if (curval.length > charWidth) {
                            $(plusDiv).hide();
                        }
                        else {
                            $(plusDiv).show();  // make sure is showing to bring back from a hide
                        }
                    }
                    else { return false; }  // prevents entering any chars other than ACGTN and backspace
                }
            });

        }
        var buttonDiv = dojo.create("div", { className: "sequence_alteration_button_div" }, content);
        var addButton = dojo.create("button", { innerHTML: "Add", className: "sequence_alteration_button" }, buttonDiv);

        var addSequenceAlteration = function() {
            var ok = true;
            var inputField;
            var inputField = ((type == "deletion") ? deleteField : plusField);
            // if (type == "deletion") { inputField = deleteField; }
            // else  { inputField = plusField; }
            var input = inputField.value.toUpperCase();
            if (input.length == 0) {
                alert("Input cannot be empty for " + type);
                ok = false;
            }
            if (ok) {
                var input = inputField.value.toUpperCase();
                if (type == "deletion") {
                    if (input.match(/\D/)) {
                        alert("The length must be a number");
                        ok = false;
                    }
                    else {
                        input = parseInt(input);
                        if (input <= 0) {
                            alert("The length must be a positive number");
                            ok = false;
                        }
                    }
                }
                else {
                    if (input.match(/[^ACGTN]/)) {
                        alert("The sequence should only containg A, C, G, T, N");
                        ok = false;
                    }
                }
            }
            if (ok) {
                var fmin = gcoord;
                var fmax;
                if (type == "insertion") {
                    fmax = gcoord;
                }
                else if (type == "deletion") {
                    fmax = gcoord + parseInt(input);
                }
                else if (type == "substitution") {
                    fmax = gcoord + input.length;;
                }
                if (track.storedFeatureCount(fmin, fmax == fmin ? fmin + 1 : fmax) > 0) {
                    alert("Cannot create overlapping sequence alterations");
                }
                else {
                    var feature = { "location": {
                        "fmin": fmin,
                        "fmax": fmax,
                        "strand": 1
                    },"type": {
                        "name":type,
                        "cv": {
                            "name":"sequence"
                        }
                    } };
                    if (type != "deletion") {
                        feature.residues= input;
                    }
                    var features = [feature];
                    var postData = {
                        "track": track.annotTrack.getUniqueTrackName(),
                        "features": features,
                        "operation": "add_sequence_alteration"
                    };
                    track.annotTrack.executeUpdateOperation(postData);
                    track.annotTrack.closeDialog();
                }
            }
        };
        
        dojo.connect(addButton, "onclick", null, function() {
            addSequenceAlteration();
        });

        return content;
    },
    loadTranslationTable: function() {
        var thisB = this;
        return xhr.post( this.context_path + "/AnnotationEditorService",
        {
            data: JSON.stringify({ "track": this.annotationPrefix+this.refSeq.name, "operation": "get_translation_table" }),
            handleAs: "json"
        }).then(function(response) {
            thisB._codonTable=thisB.generateCodonTable(response.translation_table);
            thisB.changed();
            thisB.redraw();
        },
        function(response) {
            console.log('Failed to load translation table. Setting default');
            return response;
        });
    },

});
});

