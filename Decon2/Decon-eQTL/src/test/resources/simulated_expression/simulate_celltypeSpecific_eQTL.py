import numpy as np
import argparse
import random
import sys
import os
import scipy.stats


parser = argparse.ArgumentParser(description='Simulate gene expression levels using expression ~ cc1 + cc2 + snp:cc1 + snp:cc2')
parser.add_argument('cellcount_file', help='file containing cell counts')
parser.add_argument('genotype_file', help='file containing genotypes')
parser.add_argument('out_dir', help='output directory to write the simulated data to')
parser.add_argument('number_of_samples', help='Number of samples to simulate', type=int)
parser.add_argument('number_of_snps', help='Number of snps to simulate', type=int)
parser.add_argument('batch', help='Name of the batch')

args = parser.parse_args()

if not os.path.exists(args.out_dir+'/betas/'):
    os.makedirs(args.out_dir+'/betas/')
if not os.path.exists(args.out_dir+'/genotypes/'):
    os.makedirs(args.out_dir+'/genotypes/')
if not os.path.exists(args.out_dir+'/cellcounts/'):
    os.makedirs(args.out_dir+'/cellcounts/')
if not os.path.exists(args.out_dir+'/expression/'):
    os.makedirs(args.out_dir+'/expression/')
if not os.path.exists(args.out_dir+'/snpsToTest/'):
    os.makedirs(args.out_dir+'/snpsToTest/')


def get_random_betas(n_betas):
    # get random nominal significant betas
    betas_significant = []
    x = 0.1
    for i in range(0, n_betas):
        if i % 2 == 0:
            x *= 10
        pval = random.uniform(0, 0.05/x)
        beta = scipy.stats.norm.ppf(1-pval/2)
        betas_significant.append(beta)
    # get random non-significant betas
    betas_not_significant = []
    for i in range(0, n_betas):
        pval = random.uniform(0.05, 1)
        beta = scipy.stats.norm.ppf(1-pval/2)
        betas_not_significant.append(beta)
    
    mix = betas_not_significant+betas_significant
    random.shuffle(mix)
    return({'significant':betas_significant,
            'non_significant':betas_not_significant,
            'mix':mix})

# Use actual (or Decon-Cell predicted) cell counts to simulate the expression levels. Parse the file
# File should be in format of
#          CC1    CC2
# sample1  75     14
# sample2  84     4
def simulate_expression(number_of_samples, number_of_snps, batch):
      
    cellcount_names = []
    samples = []
    cellcount_per_sample = {}
    with open(args.cellcount_file) as input_file:
        cellcount_names = input_file.readline().strip().split('\t')
        
        for line in input_file:
            line = line.strip().split('\t')
            samples.append(line[0])
            cellcount_per_sample[line[0]] = line[1:]

    sample_info = {}    
    samples_tmp = list(samples)

    random.shuffle(samples_tmp)
    random_selected_samples_list = samples_tmp[:number_of_samples]
    random_selected_samples_set = set(random_selected_samples_list)
    batch_name = 'batch'+str(batch)+'.'+str(number_of_samples)
    
    # opening outfiles
    out_beta_info = open(args.out_dir+'/betas/beta_info_cc'+batch_name+'samples.txt','w')
    out_genotype = open(args.out_dir+'/genotypes/genotypes_'+batch_name+'samples.txt','w')
    out_cellcount = open(args.out_dir+'/cellcounts/cellcounts_'+batch_name+'samples.txt','w')
    out_simulatedExpression =  open(args.out_dir+'/expression/simulated_expression_'+batch_name+'samples.txt','w')
    out_snpToTest = open(args.out_dir+'snpsToTest/snpsToTest_'+batch_name+'.txt','w') 
            
    out_beta_info.write('gene\tsnp')
    for cc in cellcount_names:
        out_beta_info.write('\t'+cc+'_beta\t'+cc+':GT_beta')
        out_cellcount.write('\t'+cc)
    out_beta_info.write('\terror\n')
    out_cellcount.write('\n')
            
               #sample_info[batch_name] = [random_selected_samples_list, random_selected_samples_set,
        #                                         out_beta_info, out_genotype, out_cellcount, out_simulatedExpression]     
    with open(args.genotype_file) as input_file:
        # read in all lines of the file so that it can be closed after
        genotype_lines = input_file.read().split('\n')
        genotype_header = genotype_lines[0].strip().split('\t')
        if not genotype_header == samples:
            for index, sample in enumerate(genotype_header):
                print(samples[index], sample)
            raise RuntimeError("header and samples not same order")
    for sample in random_selected_samples_list:
        cellcounts = cellcount_per_sample[sample]
        out_cellcount.write(sample)
        for cellcount in cellcounts:
            out_cellcount.write('\t'+cellcount)
        out_cellcount.write('\n')

    out_simulatedExpression.write('\t'+'\t'.join(random_selected_samples_list)+'\n')
    out_genotype.write('\t'+'\t'.join(random_selected_samples_list)+'\n')

    mu, sigma = 0, 4
    print('simulate betas')
    error = np.random.normal(0,1, len(genotype_lines[1:])+2)
    
    

    out_snpToTest.write('gene\tsnp\n')
    # use random genotypes
    
    genotype_lines = genotype_lines[1:number_of_snps]
    for index, line in enumerate(genotype_lines):
        if index % 100 == 0:
            print('processed',index,'lines')
            sys.stdout.flush()
        line = line.strip().split('\t')
        snp = line[0]
        if len(snp.strip()) == 0:
            continue
        out_snpToTest.write('gene_'+str(index)+'\t'+snp+'\n')

        out_simulatedExpression.write('gene_'+str(index))
        out_genotype.write(snp)
        out_beta_info.write('gene_'+str(index)+'\t'+snp)
        
        # get random betas
        betas = get_random_betas(len(cellcounts))
        current_cc_betas = betas['mix']
        random.shuffle(current_cc_betas)
        current_cc_gt_betas = betas['mix']
        random.shuffle(current_cc_gt_betas)
        #current_cc_gt_betas[-1] = random.choice(betas['significant'])
        
        #  for the cc*gt term, make sure that all betas have same direction, 50% all negative or all positive
        if random.randint(1,2) == 1:
            current_cc_gt_betas = [-1*x for x in current_cc_gt_betas]
            
        for cc_index, cellcount_name in enumerate(cellcount_names):
            out_beta_info.write('\t'+str(current_cc_betas[cc_index])+'\t'+str(current_cc_gt_betas[cc_index]))
        
        error_index =  random.randint(0,len(genotype_lines[1:])+2)
        out_beta_info.write('\t'+str(error[error_index])+'\n')
        
        for sample in random_selected_samples_list:
            sample_index = samples.index(sample)+1
            
            dosage = float(line[sample_index])
            cellcounts = cellcount_per_sample[sample]
            out_genotype.write('\t'+str(dosage))
            # Expression will be made with expression = cc1 + cc2 + snp:cc1 + snp:cc2 + error
            # so start with 0
            expression = 0
            # then add cc and cc*snp. for cc*snp can add the beta
            for cc_index, cellcount in enumerate(cellcounts):
                cc_contribution = current_cc_betas[cc_index] * float(cellcount)
                expression += cc_contribution
                cc_snp_contribution = current_cc_gt_betas[cc_index] * float(cellcount) * float(dosage)
                expression += cc_snp_contribution
            out_simulatedExpression.write('\t'+str(expression+error[error_index]))
            #out_simulatedExpression.write('\t'+str(expression+0))

        out_simulatedExpression.write('\n')
        out_genotype.write('\n')
            
    print('output written to '+args.out_dir+'/')

    out_beta_info.close()
    out_genotype.close()
    out_cellcount.close()
    out_simulatedExpression.close()
    out_snpToTest.close()
        
simulate_expression(args.number_of_samples,args.number_of_snps, args.batch)
